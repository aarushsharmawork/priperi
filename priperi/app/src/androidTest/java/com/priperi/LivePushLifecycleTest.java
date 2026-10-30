package com.priperi;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit opt-in, staged so the host can kill the app and change permission between runs. */
@RunWith(AndroidJUnit4.class)
public class LivePushLifecycleTest {
    private static final String PASSWORD="Disposable-Push-2026!";
    private static final String SENDER_VAULT="push-lifecycle-sender";
    private Context context;
    private Object local(Repository.Job<Object> job)throws Exception {
        CountDownLatch done=new CountDownLatch(1);Object[] result={null};String[] error={null};
        Repository.local(context,job,value->{result[0]=value;done.countDown();},message->{error[0]=message;done.countDown();});
        assertTrue("Local operation timed out",done.await(80,TimeUnit.SECONDS));assertNull(error[0]);return result[0];
    }
    @Test public void stagedHostedPushLifecycle() throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("livepushlife")));
        assertTrue(BuildConfig.DEBUG);assertTrue(BuildConfig.BACKEND_URL.startsWith("https://YOUR_PROJECT_REF.supabase.co/"));
        context=ApplicationProvider.getApplicationContext();Repository.reset();
        String phase=InstrumentationRegistry.getArguments().getString("phase","");
        if("prepare".equals(phase))prepare();
        else if("cold".equals(phase))verifyCold();
        else if("denied_logout".equals(phase))verifyDeniedAndLogOut();
        else if("finish".equals(phase))verifyLogoutAndDelete();
        else if("cleanup".equals(phase))cleanup();
        else fail("Select an explicit lifecycle phase");
    }
    private void prepare() throws Exception {
        local(v->{assertNull("Protect existing accounts: use a fresh disposable emulator",v.account());return null;});
        try(Vault sender=new Vault(context,SENDER_VAULT)) {
            assertNull("Protect existing test identity",sender.account());
            try {
                String suffix=UUID.randomUUID().toString().substring(0,8),receiverName="qa_cold_"+suffix,senderName="qa_from_"+suffix;
                local(v->{Session.continueAccount(v,receiverName,PASSWORD);v.setSetting("notifications",true);return null;});
                FirebaseMessaging messaging=FirebaseMessaging.getInstance();messaging.setAutoInitEnabled(true);
                String token=Tasks.await(messaging.getToken(),45,TimeUnit.SECONDS);
                local(v->{Repository.call(v,"setNotificationToken",new JSONObject().put("token",token));return null;});
                local(v->{try {
                    Session.continueAccount(sender,senderName,PASSWORD);JSONObject peer=Repository.lookup(sender,receiverName);TestIdentityTrust.pin(sender,peer);
                    JSONArray envelopes=new JSONArray();
                    for(String body:new String[]{"Cold-start private test.","Permission-denied private test.","Signed-out private test."})envelopes.put(sender.crypto().encrypt(Session.uid(),sender.identity(),peer,body));
                    // Only this disposable sender's scoped session/proof and ciphertext.
                    // Host writes its temporary copy with mode600; never print it.
                    JSONObject bridge=new JSONObject().put("token",Session.access(sender)).put("deviceSecret",sender.identity().getString("deviceSecret")).put("messages",envelopes);
                    try(FileOutputStream out=context.openFileOutput("push-lifecycle-trigger.json",Context.MODE_PRIVATE)){out.write(bridge.toString().getBytes(Crypto.UTF8));}
                    return null;
                } finally {Session.restore(v);}});
                Notices.clear(context);
            } catch(Exception|AssertionError failure) {try {cleanup();}catch(Throwable ignored){}throw failure;}
        }
    }
    private NotificationManager manager(){return context.getSystemService(NotificationManager.class);}
    private void verifyCold()throws Exception {
        assertEquals("Real FCM did not post after the process was stopped",1,manager().getActiveNotifications().length);
        android.app.Notification alert=manager().getActiveNotifications()[0].getNotification();
        assertFalse(alert.extras.toString().contains("Cold-start private test."));
        local(v->{assertNotNull(Session.uid());assertEquals("",Repository.sync(v));assertEquals("Cold-start private test.",v.messages(null).get(0).getString("body"));return null;});
        Notices.clear(context);
    }
    private void verifyDeniedAndLogOut()throws Exception {
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
        assertEquals(0,manager().getActiveNotifications().length);Notices.show(context); // no crash when permission was revoked
        local(v->{assertEquals("",Repository.sync(v));assertEquals(2,v.messages(null).size());
            v.setSetting("notifications",false);Repository.call(v,"setNotificationToken",new JSONObject().put("token",JSONObject.NULL));
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);Tasks.await(FirebaseMessaging.getInstance().deleteToken(),15,TimeUnit.SECONDS);
            Session.signOut(v);assertNull(v.session());return null;});
        Notices.clear(context);
    }
    private void verifyLogoutAndDelete()throws Exception {
        assertTrue(manager().areNotificationsEnabled());assertEquals(0,manager().getActiveNotifications().length);
        local(v->{assertNull(v.session());assertNull(Session.uid());Session.continueAccount(v,v.account().getString("username"),PASSWORD);
            assertEquals("",Repository.sync(v));assertEquals(3,v.messages(null).size());return null;});
        cleanup();
    }
    private void cleanup()throws Exception {
        try(Vault sender=new Vault(context,SENDER_VAULT)) {
            try {local(v->{try {if(sender.account()!=null){Session.continueAccount(sender,sender.account().getString("username"),PASSWORD);Repository.call(sender,"deleteAccount",new JSONObject());sender.destroy();}}finally {Session.restore(v);}return null;});}
            finally {local(v->{if(v.account()!=null){Session.continueAccount(v,v.account().getString("username"),PASSWORD);Repository.call(v,"deleteAccount",new JSONObject());}v.destroy();Session.forget();return null;});}
        }
        FirebaseMessaging.getInstance().setAutoInitEnabled(false);Tasks.await(FirebaseMessaging.getInstance().deleteToken(),15,TimeUnit.SECONDS);
        context.deleteFile("push-lifecycle-trigger.json");Notices.clear(context);Repository.reset();
    }
}
