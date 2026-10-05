package com.giino.audioprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val svc = Intent(this, ProbeService::class.java)
            intent?.extras?.let { svc.putExtras(it) }
            startForegroundService(svc)
            Log.i("AudioProbe", "service demande depuis l'activite")
        } catch (t: Throwable) {
            Log.e("AudioProbe", "demarrage du service impossible: $t")
        }
        finish()
    }
}
