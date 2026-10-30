package com.priperi;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in fresh fixture: does not spend the full suite's shared account-creation quota. */
@RunWith(AndroidJUnit4.class)
public class ContactApprovalTest {
    private Context context;private Vault peer;private String mainId,peerId;
    private void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){error[0]=e;return false;}},ok->done.countDown(),e->{error[0]=new AssertionError(e);done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));if(error[0]!=null)throw new AssertionError(error[0]);}
    @Before public void setup()throws Exception {
        Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("approval")));
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).commit();Repository.reset();
        String suffix=UUID.randomUUID().toString().substring(0,8);peer=new Vault(context,"approval_peer_"+suffix);
        local(v->{if(v.account()!=null)throw new SecurityException("Use an empty disposable installation");mainId=Session.continueAccount(v,"qa_approve_"+suffix,"Approval-Password-2026!").getString("uid");peerId=Session.continueAccount(peer,"qa_ap_peer_"+suffix,"Approval-Password-2026!").getString("uid");TestIdentityTrust.pin(peer,Repository.call(peer,"lookupUser",new JSONObject().put("uid",mainId)));Session.restore(v);TestIdentityTrust.pin(v,Repository.call(v,"lookupUser",new JSONObject().put("uid",peerId)));return true;});
    }
    @After public void cleanup()throws Exception{if(context==null)return;local(v->{Session.restore(peer);if(peer.account()!=null)Session.resetAccount(peer);Session.restore(v);if(v.account()!=null){Session.resetAccount(v);Repository.eraseLocal(v);}return true;});peer.destroy();Repository.reset();}
    @Test public void offlineCapLostResponseAndLegacyOutboxResumeWithoutResettingDeliveryCount()throws Exception {
        local(v->{
            // Historical pre-policy local messages must not deadlock a request if no server history remains.
            for(int i=0;i<3;i++){JSONObject env=v.crypto().encrypt(mainId,v.identity(),v.contact(peerId),"Old local history");v.store(new JSONObject().put("peer",peerId).put("body","Old local history").put("envelope",env).put("outgoing",true).put("state","delivered"));}
            assertEquals(3,Repository.remaining(v.contact(peerId),v.messages(peerId)));v.clearHistory(peerId);
            for(int i=0;i<3;i++)Repository.send(v,peerId,"First contact "+i);
            assertThrows(SecurityException.class,()->Repository.send(v,peerId,"Fourth message"));
            JSONObject hidden=v.messages(peerId).get(0);java.lang.reflect.Field dbField=Vault.class.getDeclaredField("db");dbField.setAccessible(true);android.database.sqlite.SQLiteDatabase testDb=(android.database.sqlite.SQLiteDatabase)dbField.get(v);testDb.beginTransaction();try{v.deleteMessage(peerId,hidden.getJSONObject("envelope").getString("id"));assertThrows(SecurityException.class,()->Repository.send(v,peerId,"Local deletion is no bypass"));}finally{testDb.endTransaction();}
            // Simulate an old/modified client with two extra already-queued encrypted records.
            for(int i=0;i<2;i++){JSONObject envelope=v.crypto().encrypt(mainId,v.identity(),v.contact(peerId),"Legacy queued "+i);v.store(new JSONObject().put("peer",peerId).put("body","Legacy queued "+i).put("envelope",envelope).put("outgoing",true).put("state","pending"));}
            Repository.sync(v);int pending=0,sent=0;for(JSONObject m:v.messages(peerId)){if("pending".equals(m.optString("state"))){pending++;assertTrue(m.optBoolean("waitingApproval"));}else sent++;}assertEquals(3,sent);assertEquals(2,pending);
            Session.restore(peer);Repository.sync(peer);assertEquals(3,peer.messages(mainId).size());Session.restore(v);
            // Server saved this message but the sender lost the success response.
            JSONObject saved=v.messages(peerId).stream().filter(m->"sent".equals(m.optString("state"))).findFirst().get();saved.put("state","pending");v.store(saved);Repository.sync(v);assertEquals("delivered",v.message(saved.getJSONObject("envelope").getString("id")).getString("state"));
            v.clearHistory(peerId);Repository.sync(v);assertEquals(0,Repository.remaining(v.contact(peerId),v.messages(peerId)));assertThrows(SecurityException.class,()->Repository.send(v,peerId,"Clearing is no bypass"));
            Session.restore(peer);Repository.approveContact(peer,mainId);Session.restore(v);Repository.sync(v);
            assertTrue(v.contact(peerId).getJSONObject("contactState").getBoolean("approved"));for(JSONObject m:v.messages(peerId))assertFalse(m.optBoolean("waitingApproval"));
            Repository.send(v,peerId,"Approved message");Repository.sync(v);Session.restore(peer);Repository.sync(peer);assertEquals(6,peer.messages(mainId).size());Session.restore(v);return true;
        });
    }
    @Test public void recipientSeesRequestAndCanApproveWithAnExplicitConfirmation()throws Exception {
        local(v->{Session.restore(peer);for(int i=0;i<3;i++)Repository.send(peer,mainId,"Please recognize me "+i);Repository.sync(peer);Session.restore(v);Repository.sync(v);return true;});
        Intent intent=new Intent(context,ChatActivity.class).putExtra("peer",peerId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<ChatActivity> chat=ActivityScenario.launch(intent)) {
            await(chat,a->{View button=find(a.root,"Approve contact");return a.root.isShown()&&button!=null&&button.isShown();});
            chat.onActivity(a->{save(a,"approval-request");find(a.root,"Approve contact").performClick();try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("approvalDialog");field.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field.get(a);assertTrue(dialog.isShowing());dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();}catch(Exception e){throw new AssertionError(e);}});
            local(v->{assertTrue(v.contact(peerId).getJSONObject("contactState").getBoolean("incomingPending"));return true;});
            chat.onActivity(a->{find(a.root,"Approve contact").performClick();try{java.lang.reflect.Field field=ChatActivity.class.getDeclaredField("approvalDialog");field.setAccessible(true);((androidx.appcompat.app.AlertDialog)field.get(a)).getButton(DialogInterface.BUTTON_POSITIVE).performClick();}catch(Exception e){throw new AssertionError(e);}});
            await(chat,a->{View button=find(a.root,"Approve contact");return button!=null&&!button.isShown();});
            local(v->{assertFalse(v.contact(peerId).getJSONObject("contactState").getBoolean("incomingPending"));assertTrue(v.contact(peerId).getJSONObject("contactState").getBoolean("approved"));assertEquals(Integer.MAX_VALUE,Repository.remaining(v.contact(peerId),v.messages(peerId)));for(int i=0;i<5;i++)Repository.send(v,peerId,"Reply after one approval "+i);Repository.sync(v);Session.restore(peer);Repository.sync(peer);assertTrue(peer.contact(mainId).getJSONObject("contactState").getBoolean("approved"));assertFalse(peer.contact(mainId).getJSONObject("contactState").getBoolean("incomingPending"));assertEquals(5,peer.messages(mainId).stream().filter(m->!m.optBoolean("outgoing")).count());Repository.send(peer,mainId,"The fourth now works");Repository.sync(peer);Session.restore(v);Repository.sync(v);return true;});
            await(chat,a->find(a.root,"The fourth now works")!=null);chat.onActivity(a->save(a,"approval-complete"));
        }
    }
    @Test public void waitingOutboxResumesImmediatelyWhenOneAcceptanceArrives()throws Exception{
        String[] waiting={null};local(v->{for(int i=0;i<3;i++)Repository.send(v,peerId,"Introduction "+i);Repository.sync(v);JSONObject env=v.crypto().encrypt(mainId,v.identity(),v.contact(peerId),"Queued until approval");waiting[0]=env.getString("id");v.store(new JSONObject().put("peer",peerId).put("body","Queued until approval").put("envelope",env).put("outgoing",true).put("state","pending").put("waitingApproval",true));Session.restore(peer);Repository.sync(peer);Repository.approveContact(peer,mainId);Session.restore(v);return true;});
        new SyncEngine((SyncEngine.Progress)null).inbox();
        boolean[] sent={false};for(int i=0;i<100&&!sent[0];i++){local(v->{sent[0]="sent".equals(v.message(waiting[0]).optString("state"));return true;});if(!sent[0])SystemClock.sleep(30);}assertTrue("Approval did not wake queued messages",sent[0]);
        local(v->{Session.restore(peer);Repository.sync(peer);assertEquals(4,peer.messages(mainId).size());assertTrue(peer.contact(mainId).getJSONObject("contactState").getBoolean("approved"));Session.restore(v);return true;});
    }
    private interface Ready{boolean test(ChatActivity a);}private static void await(ActivityScenario<ChatActivity> s,Ready r){for(int i=0;i<140;i++){boolean[] ok={false};s.onActivity(a->ok[0]=r.test(a));if(ok[0])return;SystemClock.sleep(100);}fail("Approval UI did not update");}
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void save(ChatActivity a,String name){try{View view=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));File dir=new File(a.getExternalFilesDir(null),"approval-previews");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}catch(IOException e){throw new AssertionError(e);}}
}
