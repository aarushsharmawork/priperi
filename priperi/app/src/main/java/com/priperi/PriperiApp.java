package com.priperi;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.firebase.FirebaseApp;

public final class PriperiApp extends Application {
    private static PriperiApp instance;
    static PriperiApp context() { return instance; }
    public static boolean locked = true;
    private int started = 0;
    static volatile boolean privateForeground;
    static volatile boolean foreground;
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        BackupFiles.clear(this);TransferFiles.clear(this);RecoveryFiles.clear(this);
        applyTheme(getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", -1));
        FirebaseApp.initializeApp(this);
        Notices.channels(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { DeviceSecurity.secureWindow(a); }
            public void onActivityStarted(android.app.Activity a) { started++;foreground=true;Repository.active(); }
            public void onActivityResumed(android.app.Activity a) {}
            public void onActivityPaused(android.app.Activity a) {}
            public void onActivityStopped(android.app.Activity a) { started--;foreground=started>0;if(started==0&&!a.isChangingConfigurations()){locked=true;Repository.idle();} }
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            public void onActivityDestroyed(android.app.Activity a) {}
        });
    }
    static void applyTheme(int mode) {
        AppCompatDelegate.setDefaultNightMode(mode == 1 ? AppCompatDelegate.MODE_NIGHT_YES : mode == 0 ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
