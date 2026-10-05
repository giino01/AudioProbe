package com.giino.audioprobe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Outil en ligne de commande, lance depuis le shell (uid 2000) :
 *
 *   APK=$(pm path com.giino.audioprobe | sed 's/package://')
 *   CLASSPATH=$APK app_process /system/bin com.giino.audioprobe.Poker get 1002 aa000221 aa00011e
 *   CLASSPATH=$APK app_process /system/bin com.giino.audioprobe.Poker set 1002 aa000221 2
 *
 * get <device> <fid-hex> [<fid-hex> ...]  : lit les identifiants (aucune ecriture)
 * set <device> <fid-hex> <valeur>         : lit avant, ecrit, relit apres
 * seq <device> fid=val ... wNNN ...       : ecritures enchainees, wNNN = pause NNN ms
 *
 * Tout passe par reflexion (aucune dependance Android a la compilation).
 * Chemin repris du code d'Overdrive (AvasController / BydModeCommand) :
 *   Context systeme -> createPackageContext -> getSystemService("auto") -> getInt/setInt
 */
public final class Poker {

    private static final String PKG = "com.giino.audioprobe";

    private Poker() {}

    public static void main(String[] args) {
        // garde-fou : systemMain() peut se bloquer sur certains firmwares
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(25000);
                } catch (InterruptedException e) {
                    return;
                }
                System.out.println("POKE_RESULT=timeout (25 s)");
                Runtime.getRuntime().halt(3);
            }
        }, "poke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        int code = 2;
        try {
            code = run(args);
        } catch (Throwable t) {
            Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            System.out.println("POKE_ERROR=" + c.getClass().getName() + ": " + c.getMessage());
        }
        System.out.flush();
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: get <dev> <fid-hex>... | set <dev> <fid-hex> <val>");
            return 2;
        }
        String mode = args[0];
        int dev = Integer.parseInt(args[1]);

        Object mgr = obtainAutoManager();
        if (mgr == null) {
            System.out.println("POKE_RESULT=service 'auto' indisponible");
            return 2;
        }
        System.out.println("POKE_MANAGER=" + mgr.getClass().getName());
        Method getInt = mgr.getClass().getMethod("getInt", int.class, int.class);

        if ("get".equals(mode)) {
            for (int i = 2; i < args.length; i++) {
                int fid = parseFid(args[i]);
                System.out.println("GET dev=" + dev + " fid=0x" + Integer.toHexString(fid)
                        + " -> " + callGet(getInt, mgr, dev, fid));
            }
            return 0;
        }

        if ("set".equals(mode)) {
            if (args.length != 4) {
                System.out.println("usage: set <dev> <fid-hex> <val>");
                return 2;
            }
            int fid = parseFid(args[2]);
            int val = Integer.parseInt(args[3]);
            Method setInt = mgr.getClass().getMethod("setInt", int.class, int.class, int.class);
            System.out.println("AVANT  dev=" + dev + " fid=0x" + Integer.toHexString(fid)
                    + " -> " + callGet(getInt, mgr, dev, fid));
            Object r = setInt.invoke(mgr, dev, fid, val);
            System.out.println("SET    dev=" + dev + " fid=0x" + Integer.toHexString(fid)
                    + " val=" + val + " -> retour=" + r);
            Thread.sleep(300);
            System.out.println("APRES  dev=" + dev + " fid=0x" + Integer.toHexString(fid)
                    + " -> " + callGet(getInt, mgr, dev, fid));
            return 0;
        }

        if ("seq".equals(mode)) {
            // seq <dev> fid=val fid=val w100 fid=val ...  (wNNN = pause NNN ms)
            Method setInt = mgr.getClass().getMethod("setInt", int.class, int.class, int.class);
            for (int i = 2; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("w")) {
                    Thread.sleep(Long.parseLong(a.substring(1)));
                    continue;
                }
                int eq = a.indexOf('=');
                int fid = parseFid(a.substring(0, eq));
                int val = Integer.parseInt(a.substring(eq + 1));
                Object r = setInt.invoke(mgr, dev, fid, val);
                System.out.println("SEQ " + System.currentTimeMillis() % 100000
                        + " fid=0x" + Integer.toHexString(fid) + " val=" + val + " -> " + r);
            }
            return 0;
        }

        System.out.println("mode inconnu: " + mode);
        return 2;
    }

    private static String callGet(Method getInt, Object mgr, int dev, int fid) {
        try {
            return String.valueOf(getInt.invoke(mgr, dev, fid));
        } catch (Throwable t) {
            Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            return "ERREUR " + c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }

    private static int parseFid(String s) {
        String h = s.toLowerCase();
        if (h.startsWith("0x")) h = h.substring(2);
        return (int) Long.parseLong(h, 16);
    }

    /** Meme demarche que BydModeCommand.createAppContext() d'Overdrive. */
    private static Object obtainAutoManager() throws Exception {
        Class<?> looper = Class.forName("android.os.Looper");
        if (looper.getMethod("getMainLooper").invoke(null) == null) {
            looper.getMethod("prepareMainLooper").invoke(null);
        }
        Class<?> atc = Class.forName("android.app.ActivityThread");
        Method current = atc.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object at = current.invoke(null);
        if (at == null) {
            Method systemMain = atc.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            at = systemMain.invoke(null);
        }
        Method getSysCtx = atc.getDeclaredMethod("getSystemContext");
        getSysCtx.setAccessible(true);
        Object sysCtx = getSysCtx.invoke(at);

        // CONTEXT_INCLUDE_CODE (1) | CONTEXT_IGNORE_SECURITY (2)
        Method createPkg = sysCtx.getClass().getMethod(
                "createPackageContext", String.class, int.class);
        Object ctx = createPkg.invoke(sysCtx, PKG, 3);

        Method getSvc = ctx.getClass().getMethod("getSystemService", String.class);
        Object svc = getSvc.invoke(ctx, "auto");
        if (svc != null) return svc;

        // repli : BYDAutoDeviceManager.getInstance(Context) (BydDeviceHelper d'Overdrive)
        Class<?> mc = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager");
        Method gi = mc.getMethod("getInstance", Class.forName("android.content.Context"));
        return gi.invoke(null, ctx);
    }
}
