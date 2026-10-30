package com.priperi;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.installations.FirebaseInstallations;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class SettingsActivity extends PrivateActivity {
    private LinearLayout body;
    private TextView status;
    private JSONObject account;
    private boolean notificationBusy, enableAfterPermission;
    @Override protected void onCreate(Bundle saved) { super.onCreate(saved); root = Ui.page(this); root.setVisibility(android.view.View.INVISIBLE); LinearLayout bar=Ui.header(this, root, "Settings", null, null);bar.addView(Ui.iconButton(this,"qr","Add by QR",v->startActivity(new Intent(this,ContactCodeActivity.class))));body = Ui.scroll(this, root);new AppNavigation(this,root,"settings",section->{if(!"settings".equals(section))AppNavigation.navigate(this,section);}); }
    @Override protected void ready() {
        render();
        if (enableAfterPermission) { enableAfterPermission = false; setNotifications(true); }
    }
    private void render() { render(null); }
    private void render(String notice) {
        Repository.work(this, v -> new Object[]{v.account(), v.setting("lock", false), v.setting("notifications", false)}, data -> {
            if (isFinishing() || isDestroyed()) return; account = (JSONObject) data[0]; body.removeAllViews();
            LinearLayout profile = Ui.row(this); Ui.pad(this,profile,18); profile.setBackground(Ui.shape(this,getColor(R.color.surface),18)); profile.addView(Ui.avatar(this,account.optString("username"),60));
            LinearLayout details=Ui.column(this);details.setPadding(Ui.dp(this,16),0,0,0);details.addView(Ui.title(this,"@"+account.optString("username"),22));details.addView(Ui.text(this,"Your account · this phone",13,R.color.muted));profile.addView(details,new LinearLayout.LayoutParams(0,-2,1));profile.addView(Ui.iconButton(this,"edit","Edit username",view->rename()));body.addView(profile);

            status = Ui.status(this, body);
            if (notice != null) status.setText(notice);
            else if (notificationBusy) status.setText("Updating notifications…");
            Ui.section(this, body, "Profile & appearance");
            int mode = getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", -1);
            Ui.item(this, body, "Appearance", mode == -1 ? "Follow phone settings" : mode == 0 ? "Light" : "Dark", v -> appearance(mode));
            Ui.item(this, body, "Username", "Change how people find you", v -> rename());
            Ui.section(this, body, "Privacy & security");
            Ui.item(this, body, "App lock", (boolean) data[1] ? "On · use your phone’s screen lock" : "Off", v -> toggleLock(!(boolean) data[1]));
            Ui.item(this, body, "Security key", "Compare with the people you chat with", v -> Ui.dialog(this).setTitle("Your security key").setMessage("Share this key over a trusted call or in person. It should match the key shown in your contact’s chat details.\n\n" + groupKey(account.optString("fingerprint"))).setPositiveButton("Done", null).show());
            Ui.item(this, body, "Blocked accounts", "Manage who can message you", v -> blocked());
            Ui.item(this, body, "Notifications", (boolean) data[2] ? "On · message text stays private" : "Off", v -> notifications((boolean) data[2]));
            Ui.item(this, body, "Change password", "Confirm your current password first", v -> changePassword());
            Ui.item(this,body,"Recovery backup","Keep access after reinstalling; optionally include saved chats",v->startActivity(new Intent(this,RecoveryActivity.class)));
            Ui.item(this,body,"Move account and chats","Send your account and saved chats to another phone",v->startActivity(new Intent(this,TransferActivity.class)));
            Ui.item(this,body,"Self-destruct account","Choose when an inactive account is removed",v->selfDestruct());
            Ui.section(this, body, "On this phone");
            Ui.item(this,body,"Encrypted backups","Save to Google Drive or another location",v->startActivity(new Intent(this,BackupActivity.class)));
            Ui.item(this, body, "Clear message history", "Remove saved conversations from this phone", v -> clearHistory());
            Ui.item(this, body, "How your data is handled", "Storage, delivery and deletion", v -> Ui.dialog(this).setTitle("Your data in Priperi").setMessage("Messages are encrypted before leaving your phone. The server copy is removed after your contact saves it. Undelivered messages expire after 7 days.\n\nPrivate keys and message history are encrypted in app-private storage. Automatic Android backups are disabled. Optional message backups are password-encrypted and saved only where you choose. Android screenshot and screen-recording protection is enabled.\n\nYour device identifier is scoped to this app-signing key, Android user and phone. It is not a password. If you forget your password, use the registered phone’s reset option; this removes the old account and chats. After uninstalling, a recovery kit made beforehand can restore your account using its file, 24 words, current password and fingerprint or face unlock. Without that kit or an authorised move from the old installation, the lost key cannot be recovered.\n\nDeletion removes app-controlled account records and this phone’s saved data. Contacts’ copies and provider-retained logs or backups cannot be erased by this app.").setPositiveButton("Done", null).show());
            Ui.section(this, body, "Account");
            Ui.item(this, body, "Sign out", "Keep encrypted history on this phone", v -> signOut());
            Ui.item(this, body, "Delete account", "Remove your account and saved data", v -> confirmDelete(this, () -> { Session.forget(); startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish(); }, this::error)).setTextColor(getColor(R.color.danger));
            Ui.space(this, body, 26); body.addView(Ui.text(this, "PRIPERI  /  " + BuildConfig.VERSION_NAME, 11, R.color.muted));
        }, this::error);
    }
    private void selfDestruct(){
        error("Checking your inactivity setting…");Repository.work(this,v->Repository.call(v,"getAccountPolicy",new JSONObject()),policy->{
            if(!privateContentAvailable())return;int[] days={1,7,365,0};String[] labels={"After 1 day","After 7 days","After 1 year","Never"};int current=3;for(int i=0;i<days.length;i++)if(days[i]==policy.optInt("days"))current=i;
            Ui.dialog(this).setTitle("Self-destruct account").setSingleChoiceItems(labels,current,(choice,index)->{choice.dismiss();String period=AccountPolicy.label(days[index]);
                String explanation=days[index]==0?"Your account will stay until you delete it yourself.":"Your account, username and app-controlled server records will be deleted after "+period+" without opening and using Priperi online. Open Priperi online before that deadline to keep the account. Background notifications do not count. Checks run hourly while the service is available. An outage or service pause can delay deletion.\n\nAn offline phone cannot be erased remotely. Its saved app data is removed when it reconnects and confirms deletion. Contacts’ copies, exported files and provider backups or logs cannot be erased by Priperi.";
                Ui.dialog(this).setTitle(days[index]==0?"Turn off self-destruct?":"Delete after "+period+" of inactivity?").setMessage(explanation).setNegativeButton("Cancel",null).setPositiveButton("Confirm",(confirm,which)->reauthenticate(this,()->Repository.work(this,v->{JSONObject saved=Repository.call(v,"setSelfDestruct",new JSONObject().put("days",days[index]));v.accountPolicy(saved);return true;},ok->error("Self-destruct: "+period+"."),this::error),this::error)).show();
            }).setNegativeButton("Cancel",null).show();
        },this::error);
    }
    private void appearance(int current) {
        Ui.dialog(this).setTitle("Appearance").setSingleChoiceItems(new String[]{"Follow phone settings", "Light", "Dark"}, current + 1, (dialog, choice) -> {
            int mode = choice - 1; getSharedPreferences("appearance", MODE_PRIVATE).edit().putInt("theme", mode).apply(); dialog.dismiss(); PriperiApp.applyTheme(mode); render();
        }).setNegativeButton("Cancel", null).show();
    }
    private void rename() {
        LinearLayout box = Ui.column(this); Ui.pad(this, box, 24); EditText edit = Ui.input(this, box, "Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); edit.setText(account.optString("username"));
        androidx.appcompat.app.AlertDialog dialog = Ui.dialog(this).setTitle("Change username").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(view -> {
            String name = Ui.value(edit); if (!Validation.username(name)) { edit.setError("3–24 lowercase letters, numbers or _. Start with a letter."); return; } dialog.getButton(-1).setEnabled(false);
            Repository.work(this, v -> { Repository.call(v, "renameAccount", new JSONObject().put("username", name)); JSONObject updated = v.account().put("username", name); v.account(updated); return true; }, ok -> { dialog.dismiss(); render(); }, message -> { dialog.getButton(-1).setEnabled(true); edit.setError(message); });
        })); dialog.show();
    }
    private void toggleLock(boolean enabled) {
        if (enabled && !getSystemService(KeyguardManager.class).isDeviceSecure()) { error("Set a screen lock on your phone first."); return; }
        Repository.work(this, v -> { v.setSetting("lock", enabled); return true; }, ok -> { PriperiApp.locked = false; render(); }, this::error);
    }
    private void blocked() {
        Repository.work(this, Vault::contacts, contacts -> {
            List<JSONObject> blocked = new ArrayList<>(); for (JSONObject peer : contacts) if (peer.optBoolean("blocked")) blocked.add(peer);
            if (blocked.isEmpty()) { error("You haven’t blocked anyone."); return; }
            String[] names = new String[blocked.size()]; for (int i = 0; i < names.length; i++) names[i] = "@" + blocked.get(i).optString("username");
            Ui.dialog(this).setTitle("Tap an account to unblock").setItems(names, (d,which) -> {
                String uid = blocked.get(which).optString("uid"); Repository.work(this, v -> { Repository.call(v, "setBlocked", new JSONObject().put("uid", uid).put("blocked", false)); v.contactFlag(uid, "blocked", false); return true; }, ok -> error("Account unblocked."), this::error);
            }).setNegativeButton("Done", null).show();
        }, this::error);
    }
    private void notifications(boolean enabled) {
        if (notificationBusy) { error("Updating notifications…"); return; }
        Ui.dialog(this).setTitle("Message notifications").setSingleChoiceItems(new String[]{"On", "Off"},enabled?0:1,(dialog,choice)->{
            dialog.dismiss();if(choice==0)requestNotificationEnable();else setNotifications(false);
        }).setNeutralButton("Phone settings",(dialog,which)->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))).setNegativeButton("Cancel",null).show();
    }
    private void requestNotificationEnable(){if(notificationBusy)return;if(getSharedPreferences("notification_access",MODE_PRIVATE).getBoolean("settings_denied",false)&&!Notices.allowed(this)){Ui.dialog(this).setTitle("Allow message alerts").setMessage("Enable Priperi’s notifications in your phone settings.").setNegativeButton("Cancel",null).setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))).show();return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},49);return;}if(!Notices.allowed(this)){Ui.dialog(this).setTitle("Allow message alerts").setMessage("Notifications are blocked by your phone. Turn on Priperi’s message alerts in phone settings.").setNegativeButton("Cancel",null).setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))).show();return;}setNotifications(true);}
    private void setNotifications(boolean enabled) {
        if (notificationBusy) return;
        if (enabled && !Notices.allowed(this)) { error("Allow notifications in your phone settings first."); return; }
        String user = Session.uid(); if (user == null) { error("Sign in again."); return; }
        notificationBusy = true; error("Updating notifications…");
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        if (enabled) {
            messaging.setAutoInitEnabled(true);
            // Token retrieval may wait on Google Play services. Never block the
            // vault worker: permission-return and account checks need it too.
            messaging.getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    if (user.equals(Session.uid())) messaging.setAutoInitEnabled(false);
                    finishNotifications("Couldn’t enable notifications. Check your connection and try again."); return;
                }
                Repository.work(getApplicationContext(), v -> {
                    if (!user.equals(Session.uid())) throw new SecurityException("Sign in again.");
                    Repository.call(v,"setNotificationToken",new JSONObject().put("token",task.getResult()));
                    v.setSetting("notifications",true); return true;
                }, ok -> finishNotifications("Notifications are on."), this::finishNotifications);
            });
        } else {
            messaging.setAutoInitEnabled(false); Notices.dismiss(this);
            Repository.work(getApplicationContext(), v -> {
                if (!user.equals(Session.uid())) throw new SecurityException("Sign in again.");
                v.setSetting("notifications",false);
                Repository.call(v,"setNotificationToken",new JSONObject().put("token",JSONObject.NULL)); return true;
            }, ok -> {
                // Provider cleanup is best effort; server/local suppression is
                // already saved. Do not hold the account worker for this task.
                messaging.deleteToken(); finishNotifications("Notifications are off.");
            }, this::finishNotifications);
        }
    }
    private void finishNotifications(String message) {
        notificationBusy = false;
        if (!isFinishing() && !isDestroyed()) render(message);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 49) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                // Finish the private-screen gate before starting registration.
                // Android commonly delivers this callback before onResume.
                enableAfterPermission = true;
                if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && root.isShown()) {
                    enableAfterPermission = false; setNotifications(true);
                }
            }
            else {getSharedPreferences("notification_access",MODE_PRIVATE).edit().putBoolean("settings_denied",true).apply();render("Notifications are off. Tap Notifications for phone settings.");}
        }
    }
    private void clearHistory() {
        Ui.dialog(this).setTitle("Clear message history?").setMessage("This removes saved messages from this phone. Your contacts keep their copies. Messages and changes waiting to send stay in the outbox.").setNegativeButton("Cancel", null).setPositiveButton("Clear history", (d,w) -> Repository.work(this, v -> { v.clearHistory(null); return true; }, ok -> error("Message history cleared."), this::error)).show();
    }
    private void changePassword() {
        reauthenticate(this, () -> {
            LinearLayout box = Ui.column(this); Ui.pad(this, box, 24); EditText edit = Ui.input(this, box, "New password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            androidx.appcompat.app.AlertDialog dialog = Ui.dialog(this).setTitle("Choose a new password").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(view -> {
                String pass = edit.getText().toString(); if (!Validation.password(pass)) { edit.setError("12–72 characters with uppercase, lowercase, a number and a symbol."); return; } dialog.getButton(-1).setEnabled(false);
                Repository.work(this,v->{
                    Repository.call(v,"changePassword",new JSONObject().put("password",pass));
                    // Hosted Auth revokes sessions when an admin changes a password.
                    return Session.continueAccount(v,null,pass);
                },ok->{edit.setText("");dialog.dismiss();error("Password changed.");},message->{edit.setText("");dialog.getButton(-1).setEnabled(true);edit.setError(message);});
            })); dialog.show();
        }, this::error);
    }
    private void signOut() {
        Ui.dialog(this).setTitle("Sign out?").setMessage("Encrypted history stays on this phone. Sign back in here to open it.").setNegativeButton("Cancel", null).setPositiveButton("Sign out", (d,w) -> {
            Repository.work(this,v->{
                try{Repository.call(v,"setNotificationToken",new JSONObject().put("token",JSONObject.NULL));}catch(Exception ignored){}
                FirebaseMessaging.getInstance().setAutoInitEnabled(false);
                try{Tasks.await(FirebaseMessaging.getInstance().deleteToken(),15,TimeUnit.SECONDS);}catch(Exception ignored){}
                try{Session.signOut(v);}catch(Exception ignored){} return true;
            },ok->finishSignOut(),this::error);
        }).show();
    }
    private void finishSignOut() { FirebaseMessaging.getInstance().setAutoInitEnabled(false); Notices.clear(this); Session.forget(); Repository.reset(); PriperiApp.locked = true; startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish(); }
    static void confirmDelete(AppCompatActivity activity, Runnable done, Repository.Failure failure) {
        Ui.dialog(activity).setTitle("Delete your account?").setMessage("Your account, username, queued messages and server records will be deleted. This phone’s history and private keys will be removed after the server confirms deletion.\n\nCopies on your contacts’ phones, your manually saved backup files, and provider backups or service logs cannot be erased by this app. This cannot be undone.")
            .setNegativeButton("Keep account", null).setPositiveButton("Continue", (dialog,which) -> deleteConfirmed(activity,done,failure,true)).show();
    }
    private static void deleteConfirmed(AppCompatActivity activity,Runnable done,Repository.Failure failure,boolean askPassword) {
                Repository.work(activity, v -> {
                    try { Repository.call(v, "deleteAccount", new JSONObject()); }
                    catch(Session.BackendException e) { if(e.status==401)return false;throw e; }
                    // Destroy local keys only after the server confirms its atomic deletion.
                    removeLocalAccount(activity,v); return true;
                }, ok -> {
                    if(!ok) {if(askPassword)reauthenticate(activity,()->deleteConfirmed(activity,done,failure,false),failure);else failure.fail("Enter your password again and retry deletion.");return;}
                    Notices.clear(activity); PriperiApp.locked = true; done.run();
                }, failure);
    }
    static void reauthenticate(AppCompatActivity activity, Runnable done, Repository.Failure failure) {
        if(Session.uid()==null){failure.fail("Sign in again.");return;}
        LinearLayout box = Ui.column(activity); Ui.pad(activity, box, 24); EditText pass = Ui.input(activity, box, "Current password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        androidx.appcompat.app.AlertDialog dialog = Ui.dialog(activity).setTitle("Confirm it’s you").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(view -> {
            String value = pass.getText().toString(); if (value.isEmpty()) { pass.setError("Enter your password."); return; } dialog.getButton(-1).setEnabled(false);
            Repository.work(activity,v->Session.continueAccount(v,null,value),account->{pass.setText("");dialog.dismiss();done.run();},message->{pass.setText("");dialog.getButton(-1).setEnabled(true);pass.setError("Couldn’t confirm your password. Try again.");});
        })); dialog.show();
    }
    static String groupKey(String key) { return key.replaceAll("(.{4})(?!$)", "$1 "); }
    static void removeLocalAccount(Context context,Vault vault) throws Exception {
        // Called only after server confirmation. Erase local secrets before best-effort provider cleanup.
        BackupFiles.clear(context);TransferFiles.clear(context);RecoveryFiles.clear(context);Repository.eraseLocal(vault);BiometricGate.clearKey();AccountPolicy.forget();Notices.clear(context);context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().apply();
        try{FirebaseMessaging messaging=FirebaseMessaging.getInstance();messaging.setAutoInitEnabled(false);messaging.deleteToken();FirebaseInstallations.getInstance().delete();}catch(RuntimeException ignored){/* Provider failure must not prevent local erasure. */}
    }
    private void error(String message) { if (status != null) status.setText(message); }
}
