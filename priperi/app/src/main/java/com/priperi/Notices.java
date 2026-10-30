package com.priperi;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.*;
import androidx.core.content.ContextCompat;

final class Notices {
    private static final String CHANNEL = "private_messages";
    static final int STARTUP_PERMISSION = 73;
    private static android.content.SharedPreferences access(Context context) { return context.getSharedPreferences("notification_access",Context.MODE_PRIVATE); }
    static boolean requestAtStart(Activity activity) {
        if (access(activity).getBoolean("asked",false)) return false;
        access(activity).edit().putBoolean("asked",true).apply();
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},STARTUP_PERMISSION); return true;
        }
        return false;
    }
    static void startupPermissionResult(Context context, int[] results) {
        access(context).edit().putBoolean("enable_after_sign_in",results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED).apply();
    }
    static void channels(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Private message alerts. Message text is never shown."); channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean allowed(Context context){NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(CHANNEL);return NotificationManagerCompat.from(context).areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);}
    static void received(String user,java.util.Set<String> peers){
        Context app=PriperiApp.context();
        Repository.work(app,v->{
            // The final mute check and notification emission share the vault actor,
            // so a mute saved during network delivery cannot race a later callback.
            if(user.equals(Session.uid())&&v.setting("notifications",false)&&!PriperiApp.foreground&&allowed(app)&&peers.stream().anyMatch(peer->!v.muted(peer)))show(app);
            return true;
        },null,ignored->{});
    }
    static void show(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        androidx.core.app.Person person = new androidx.core.app.Person.Builder().setName("Priperi").setKey("priperi").build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(new androidx.core.app.Person.Builder().setName("You").build())
            .addMessage("Open Priperi to read your messages.", System.currentTimeMillis(), person);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("New message")
            .setContentText("Open Priperi to read it.").setStyle(style).setCategory(NotificationCompat.CATEGORY_MESSAGE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Priperi").setContentText("New message").build())
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setGroup("priperi.messages").build();
        NotificationManagerCompat.from(context).notify(100, notification);
    }
    private static String registeredUid;
    private static long registeredAt;
    static void refreshToken(Context context) {
        String user = Session.uid();
        if (user == null || (!access(context).getBoolean("enable_after_sign_in",false) && user.equals(registeredUid) && System.currentTimeMillis() - registeredAt < 60_000)) return;
        Context app = context.getApplicationContext();
        Repository.work(app, v -> {
            if (!user.equals(Session.uid()) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return false;
            // An explicit saved opt-out survives upgrades. A new account can use
            // the permission granted at startup (or Android's pre-13 default).
            if (access(app).getBoolean("enable_after_sign_in",false) || !v.hasSetting("notifications")) {
                v.setSetting("notifications",true); access(app).edit().putBoolean("enable_after_sign_in",false).apply();
            }
            return v.setting("notifications",false);
        }, enabled -> {
            if (!enabled || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return;
            com.google.firebase.messaging.FirebaseMessaging messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance(); messaging.setAutoInitEnabled(true);
            messaging.getToken().addOnSuccessListener(token -> Repository.work(app, v -> {
                if (!user.equals(Session.uid()) || !v.setting("notifications", false)) return false;
                Repository.call(v, "setNotificationToken", new org.json.JSONObject().put("token", token)); return true;
            }, ok -> { if (ok) { registeredUid = user; registeredAt = System.currentTimeMillis(); } }, ignored -> {}));
        }, ignored -> {});
    }
    static void dismiss(Context context) { NotificationManagerCompat.from(context).cancelAll(); }
    static void clear(Context context) { registeredUid = null; registeredAt = 0; NotificationManagerCompat.from(context).cancelAll(); }
}
