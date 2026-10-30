package com.priperi;

import android.app.Activity;
import android.content.Context;
import android.net.*;
import android.os.Build;
import android.os.Debug;
import android.view.WindowManager;
import java.io.File;
import java.util.Locale;

/** Defense in depth. Local signals are not server-side device attestation. */
final class DeviceSecurity {
    private DeviceSecurity() {}
    static void secureWindow(Activity activity) {
        secureWindow(activity.getWindow());
    }
    static void secureWindow(android.view.Window window){
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(Build.VERSION.SDK_INT>=31)window.setHideOverlayWindows(true);
    }
    static String blockedReason(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return "Network protection is unavailable. Restart your phone and try again.";
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    return "Disconnect your VPN to use Priperi. Your account and messages have not been changed.";
            }
            if (!BuildConfig.ALLOW_TEST_DEVICES) {
                if (isEmulator(Build.FINGERPRINT, Build.MODEL, Build.HARDWARE, Build.PRODUCT, Build.MANUFACTURER))
                    return "Use Priperi on a physical Android phone. Emulators are not supported.";
                if (rooted()) return "This phone appears to be rooted or running an unsupported system. Priperi is locked to protect your chats.";
                if (!BuildConfig.DEBUG && (Debug.isDebuggerConnected() || Debug.waitingForDebugger()))
                    return "Close the debugger before opening Priperi.";
            }
            return null;
        } catch (RuntimeException error) { return "The phone’s security checks could not finish. Restart the app and try again."; }
    }
    static boolean isEmulator(String fingerprint, String model, String hardware, String product, String manufacturer) {
        String f = safe(fingerprint), m = safe(model), h = safe(hardware), p = safe(product), maker = safe(manufacturer);
        return f.startsWith("generic") || f.contains("emulator") || f.contains("vbox") || m.contains("sdk_gphone") || m.contains("emulator")
            || m.contains("android sdk built for") || h.equals("goldfish") || h.equals("ranchu") || h.contains("vbox")
            || p.startsWith("sdk_") || p.contains("vbox") || maker.contains("genymotion");
    }
    private static String safe(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static boolean rooted() {
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) return true;
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/ksu", "/debug_ramdisk/.magisk"})
            if (new File(path).exists()) return true;
        return false;
    }
    static void requireAllowed() {
        Context context = PriperiApp.context();
        if (context == null) throw new SecurityException("Restart Priperi to finish its security checks.");
        String reason = blockedReason(context); if (reason != null) throw new SecurityException(reason);
    }
}
