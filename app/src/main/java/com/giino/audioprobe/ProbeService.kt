package com.giino.audioprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Test : joue un mp3 avec un MediaPlayer ordinaire et mesure le niveau du micro
 * 5 s avant, pendant (12 s max) et 5 s apres la lecture.
 *
 * Lancement (depuis le shell) :
 *   am start-foreground-service -n com.giino.audioprobe/.ProbeService \
 *       --es path /sdcard/Electro/audio/nunu_1.mp3 --ei src 1
 *
 * Extras : path = fichier mp3 ; src = source micro (1 MIC, 5 CAMCORDER, 6 VOICE_RECOGNITION,
 * 7 VOICE_COMMUNICATION, 9 UNPROCESSED) ; vol = index de volume musique a appliquer (optionnel,
 * restaure a la fin).
 *
 * Resultat : <stockage externe de l'app>/files/result.txt et logcat (tag AudioProbe).
 */
class ProbeService : Service() {

    companion object {
        private const val TAG = "AudioProbe"
        private const val DEFAULT_PATH = "/sdcard/Electro/audio/nunu_1.mp3"
        private const val RATE = 16000
        private const val PHASE_MS = 5000L
        private const val MAX_PLAY_MS = 12000L
        private const val HARD_LIMIT_MS = 45000L
    }

    private class Stats {
        var n = 0
        var zero = 0
        var sumDb = 0.0
        var minDb = 999.0
        var maxDb = -999.0
        var peak = 0

        fun add(db: Double, isZero: Boolean, p: Int) {
            n++
            if (isZero) zero++
            sumDb += db
            if (db < minDb) minDb = db
            if (db > maxDb) maxDb = db
            if (p > peak) peak = p
        }

        fun avg(): Double = if (n == 0) Double.NaN else sumDb / n

        fun text(): String =
            if (n == 0) {
                "aucune mesure"
            } else {
                String.format(
                    Locale.US,
                    "blocs=%d moy=%.1f min=%.1f max=%.1f dBFS, pic=%d, blocs_zero=%d",
                    n, avg(), minDb, maxDb, peak, zero
                )
            }
    }

    @Volatile
    private var playbackDone = false
    private val report = StringBuilder()

    @Synchronized
    private fun say(msg: String) {
        Log.i(TAG, msg)
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        report.append(ts).append(' ').append(msg).append('\n')
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        val path = intent?.getStringExtra("path") ?: DEFAULT_PATH
        val source = intent?.getIntExtra("src", MediaRecorder.AudioSource.MIC)
            ?: MediaRecorder.AudioSource.MIC
        val vol = intent?.getIntExtra("vol", -1) ?: -1

        Thread {
            try {
                runProbe(path, source, vol)
            } catch (t: Throwable) {
                say("ERREUR inattendue: $t")
            } finally {
                saveReport()
                stopForeground(true)
                stopSelf(startId)
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("probe", "AudioProbe", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "probe")
            .setContentTitle("AudioProbe")
            .setContentText("Test audio en cours")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    @Synchronized
    private fun saveReport() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "result.txt")
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("===== $stamp =====\n$report\n")
            Log.i(TAG, "rapport ecrit: ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "ecriture du rapport impossible: $t")
        }
    }

    private fun runProbe(path: String, source: Int, vol: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val file = File(path)
        say(
            "debut: sdk=${Build.VERSION.SDK_INT} source=$source fichier=$path " +
                "existe=${file.exists()} lisible=${file.canRead()} taille=${file.length()}"
        )

        val streamMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        say(
            "volume musique=$volBefore/$streamMax micMute=${am.isMicrophoneMute} " +
                "mode=${am.mode} musiqueActive=${am.isMusicActive} " +
                "enregistrementsActifs=${am.activeRecordingConfigurations.size}"
        )

        if (vol >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.coerceIn(0, streamMax), 0)
            say("volume regle a ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/$streamMax")
        }

        try {
            measure(am, file, source)
        } finally {
            if (vol >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0)
                say("volume restaure a $volBefore")
            }
        }
    }

    private fun measure(am: AudioManager, file: File, source: Int) {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            say("getMinBufferSize a echoue: $minBuf")
            return
        }

        val chunk = RATE / 10 // 100 ms
        val rec = try {
            AudioRecord(
                source, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, chunk * 8)
            )
        } catch (t: Throwable) {
            say("creation AudioRecord impossible: $t")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            say("AudioRecord non initialise (etat=${rec.state}) - permission micro accordee ?")
            rec.release()
            return
        }

        val focusListener = AudioManager.OnAudioFocusChangeListener { }
        var player: MediaPlayer? = null
        val a = Stats()
        val b = Stats()
        val c = Stats()

        try {
            rec.startRecording()
            say("enregistrement etat=${rec.recordingState} (3 = en cours) session=${rec.audioSessionId}")
            say("--- phase A : avant lecture ---")

            val buf = ShortArray(chunk)
            val t0 = SystemClock.elapsedRealtime()
            var phase = 'A'
            var tPhase = 0L
            var errors = 0
            var secSum = 0.0
            var secN = 0
            var secPhase = 'A'
            var lastSec = 0L

            while (true) {
                val now = SystemClock.elapsedRealtime() - t0
                if (now > HARD_LIMIT_MS) {
                    say("limite de temps atteinte")
                    break
                }
                if (phase == 'A' && now >= PHASE_MS) {
                    phase = 'B'
                    tPhase = now
                    say("--- phase B : lecture ---")
                    am.requestAudioFocus(
                        focusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    )
                    player = startPlayer(file)
                } else if (phase == 'B' && (playbackDone || now - tPhase >= MAX_PLAY_MS)) {
                    stopPlayer(player)
                    player = null
                    am.abandonAudioFocus(focusListener)
                    phase = 'C'
                    tPhase = now
                    say("--- phase C : apres lecture ---")
                } else if (phase == 'C' && now - tPhase >= PHASE_MS) {
                    break
                }

                val n = rec.read(buf, 0, chunk)
                if (n <= 0) {
                    errors++
                    say("lecture micro: $n")
                    if (errors >= 3) break
                    continue
                }

                var sumSq = 0.0
                var peak = 0
                var nonZero = false
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    sumSq += (s * s).toDouble()
                    val ab = if (s < 0) -s else s
                    if (ab > peak) peak = ab
                    if (s != 0) nonZero = true
                }
                val rms = sqrt(sumSq / n)
                val db = if (rms < 1.0) -120.0 else 20.0 * log10(rms / 32768.0)

                when (phase) {
                    'A' -> a.add(db, !nonZero, peak)
                    'B' -> b.add(db, !nonZero, peak)
                    else -> c.add(db, !nonZero, peak)
                }

                if (secN == 0) secPhase = phase
                secSum += db
                secN++
                val sec = now / 1000
                if (sec != lastSec) {
                    say(
                        String.format(
                            Locale.US, "t=%ds phase=%c niveau=%.1f dBFS",
                            lastSec, secPhase, secSum / secN
                        )
                    )
                    secSum = 0.0
                    secN = 0
                    lastSec = sec
                }
            }
        } catch (t: Throwable) {
            say("erreur pendant la mesure: $t")
        } finally {
            stopPlayer(player)
            am.abandonAudioFocus(focusListener)
            try {
                rec.stop()
            } catch (e: Throwable) {
                // ignore
            }
            rec.release()
        }

        say("RESUME A (avant)  : ${a.text()}")
        say("RESUME B (pendant): ${b.text()}")
        say("RESUME C (apres)  : ${c.text()}")
        if (a.n > 0 && b.n > 0) {
            say(
                String.format(
                    Locale.US, "ecart B-A = %.1f dB ; ecart C-A = %.1f dB",
                    b.avg() - a.avg(), c.avg() - a.avg()
                )
            )
        }
    }

    private fun startPlayer(file: File): MediaPlayer? {
        playbackDone = false
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                say("lecture terminee")
                playbackDone = true
            }
            mp.setOnErrorListener { _, what, extra ->
                say("erreur lecture what=$what extra=$extra")
                playbackDone = true
                true
            }
            mp.prepare()
            say("lecteur prepare, duree=${mp.duration} ms")
            mp.start()
            say("lecture demarree")
            mp
        } catch (t: Throwable) {
            say("lecteur en echec: $t")
            playbackDone = true
            null
        }
    }

    private fun stopPlayer(mp: MediaPlayer?) {
        if (mp == null) return
        try {
            if (mp.isPlaying) mp.stop()
        } catch (e: Throwable) {
            // ignore
        }
        try {
            mp.release()
        } catch (e: Throwable) {
            // ignore
        }
    }
}
