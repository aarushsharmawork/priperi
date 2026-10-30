package com.priperi;

import android.app.NotificationManager;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in, fresh disposable emulator only. Never clears an existing account. */
@RunWith(AndroidJUnit4.class)
public class LivePushTest {
    private Context context;
    private Object local(Repository.Job<Object> job)throws Exception {
        CountDownLatch done=new CountDownLatch(1);Object[] result={null};String[] error={null};
        Repository.local(context,job,value->{result[0]=value;done.countDown();},message->{error[0]=message;done.countDown();});
        assertTrue("Local operation timed out",done.await(80,TimeUnit.SECONDS));assertNull(error[0]);return result[0];
    }
    @Test public void genericPushArrivesAndOptOutStopsAlerts() throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("livepush")));
        assertTrue(BuildConfig.DEBUG);assertTrue(BuildConfig.BACKEND_URL.startsWith("https://YOUR_PROJECT_REF.supabase.co/"));
        context=ApplicationProvider.getApplicationContext();Repository.reset();
        local(v->{assertNull("Use a fresh disposable emulator; existing accounts are protected",v.account());return null;});
        String suffix=UUID.randomUUID().toString().substring(0,8),receiverName="qa_push_"+suffix,senderName="qa_send_"+suffix;
        String password="Push-"+UUID.randomUUID()+"!9";Vault sender=new Vault(context,"push_sender_"+suffix);
        boolean[] created={false,false},deleted={false,false};
        FirebaseMessaging messaging=FirebaseMessaging.getInstance();NotificationManager notices=context.getSystemService(NotificationManager.class);
        try {
            String receiverId=(String)local(v->{String id=Session.continueAccount(v,receiverName,password).getString("uid");created[0]=true;v.setSetting("notifications",true);return id;});
            assertTrue("Enable notification permission for this disposable test",notices.areNotificationsEnabled());
            messaging.setAutoInitEnabled(true);String token=Tasks.await(messaging.getToken(),45,TimeUnit.SECONDS);
            local(v->{Repository.call(v,"setNotificationToken",new JSONObject().put("token",token));return null;});
            local(v->{try {Session.continueAccount(sender,senderName,password);created[1]=true;TestIdentityTrust.pin(sender,Repository.lookup(sender,receiverName));return null;} finally {Session.restore(v);}});
            Notices.dismiss(context);
            local(v->{try {Session.restore(sender);Repository.send(sender,receiverId,"Private push test content.");Repository.sync(sender);return null;}finally {Session.restore(v);}});
            long until=SystemClock.elapsedRealtime()+60000;
            while(notices.getActiveNotifications().length==0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            assertEquals("Generic FCM alert did not arrive",1,notices.getActiveNotifications().length);
            android.app.Notification alert=notices.getActiveNotifications()[0].getNotification();
            assertFalse(alert.extras.toString().contains("Private push test content."));assertFalse(alert.extras.toString().contains(senderName));
            // FCM should trigger encrypted delivery immediately, without an
            // open inbox or a manual refresh. Ack must follow the durable save.
            long deliveryEnd=SystemClock.elapsedRealtime()+20000;boolean saved=false;
            while(!saved&&SystemClock.elapsedRealtime()<deliveryEnd) {
                saved=(Boolean)local(v->{for(JSONObject record:v.messages(null))if("Private push test content.".equals(record.optString("body")))return true;return false;});
                if(!saved)SystemClock.sleep(100);
            }
            assertTrue("Push arrived but did not trigger durable background delivery",saved);
            local(v->{assertEquals(0,Repository.call(v,"syncInbox",new JSONObject()).getJSONArray("messages").length());return null;});
            local(v->{v.setSetting("notifications",false);Repository.call(v,"setNotificationToken",new JSONObject().put("token",JSONObject.NULL));return null;});
            Notices.dismiss(context);
            local(v->{try {Session.restore(sender);Repository.send(sender,receiverId,"Opted-out push test.");Repository.sync(sender);return null;}finally {Session.restore(v);}});
            SystemClock.sleep(5000);assertEquals(0,notices.getActiveNotifications().length);
        } finally {
            try {
                try {local(v->{try {if(created[1]){Session.restore(sender);Repository.call(sender,"deleteAccount",new JSONObject());deleted[1]=true;}}finally {Session.restore(v);}return null;});}
                finally {local(v->{if(created[0]){Repository.call(v,"deleteAccount",new JSONObject());deleted[0]=true;}return null;});}
            } finally {
                messaging.setAutoInitEnabled(false);
                try {Tasks.await(messaging.deleteToken(),15,TimeUnit.SECONDS);}
                finally {
                    Notices.clear(context);
                    if(!created[1]||deleted[1])sender.destroy();else sender.close();
                    // Retain encrypted identity/session if remote cleanup needs retry.
                    if(!created[0]||deleted[0])local(v->{v.destroy();Session.forget();return null;});
                    Repository.reset();
                }
            }
        }
    }
}
