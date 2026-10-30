package com.priperi;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Fresh local fixture only; provider files and accounts are synthetic and explicitly scoped. */
@RunWith(AndroidJUnit4.class)
public class BackupFlowTest {
    private Context context;private Vault peer;private String owner,peerId;private final String login="Backup-Test-Password-2026!",password="four independent backup words 2026!";private byte[] key,salt;private final List<File> files=new ArrayList<>();
    private void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){error[0]=e;return false;}},ok->done.countDown(),e->{error[0]=new AssertionError(e);done.countDown();});assertTrue(done.await(60,TimeUnit.SECONDS));if(error[0]!=null)throw new AssertionError(error[0]);}
    @Before public void setup()throws Exception{
        Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("backup")));context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).commit();Repository.reset();String suffix=UUID.randomUUID().toString().substring(0,8);peer=new Vault(context,"backup_peer_"+suffix);
        local(v->{if(v.account()!=null)throw new SecurityException("Use an empty disposable installation");owner=Session.continueAccount(v,"qa_bak_"+suffix,login).getString("uid");peerId=Session.continueAccount(peer,"qa_bkp_"+suffix,login).getString("uid");TestIdentityTrust.pin(peer,Repository.call(peer,"lookupUser",new JSONObject().put("uid",owner)));Session.restore(v);TestIdentityTrust.pin(v,Repository.call(v,"lookupUser",new JSONObject().put("uid",peerId)));
            for(int i=0;i<2;i++){JSONObject env=v.crypto().encrypt(owner,v.identity(),v.contact(peerId),"Saved outgoing "+i);v.store(new JSONObject().put("peer",peerId).put("body","Saved outgoing "+i).put("envelope",env).put("outgoing",true).put("state","delivered").put("deviceSecret","must not enter archive"));}
            JSONObject env=peer.crypto().encrypt(peerId,peer.identity(),v.account(),"Saved incoming");v.store(new JSONObject().put("peer",peerId).put("body","Saved incoming").put("envelope",env).put("outgoing",false).put("state","received"));Repository.send(v,peerId,"Unsent message stays local");return true;});salt=BackupCodec.salt();key=BackupCodec.key(password.toCharArray(),salt);
    }
    @After public void cleanup()throws Exception{if(context==null)return;local(v->{Session.restore(peer);if(peer.account()!=null)Session.resetAccount(peer);Session.restore(v);if(v.account()!=null){Session.resetAccount(v);Repository.eraseLocal(v);}return true;});peer.destroy();Repository.reset();for(File f:files)f.delete();Arrays.fill(key,(byte)0);}
    private File file()throws Exception{File f=File.createTempFile("priperi-backup-test-",".enc",context.getCacheDir());files.add(f);return f;}
    @Test public void signedRestoreMergesWithoutSendingOrChangingKeysSettingsAndPendingMessages()throws Exception{
        File archive=file();local(v->{String before=v.identity().toString(),session=v.session().toString();v.setSetting("lock",true);v.contactFlag(peerId,"blocked",true);assertEquals(3,v.exportMessages(archive,key,salt,()->{}));
            List<JSONObject> frames=new ArrayList<>();try(InputStream in=new FileInputStream(archive)){BackupCodec.read(in,key,owner,v.identity().getString("fingerprint"),bytes->frames.add(new JSONObject(BackupCodec.utf8(bytes))),v.crypto()::verifyBackup,()->{});}for(JSONObject m:frames){assertFalse(m.has("deviceSecret"));assertFalse(m.has("session"));assertFalse(m.has("contactState"));}
            v.clearHistory(null);assertEquals(1,v.messages(null).size());assertEquals(3,v.restoreMessages(archive,key,()->{}));assertEquals(0,v.restoreMessages(archive,key,()->{}));assertEquals(4,v.messages(null).size());assertEquals(before,v.identity().toString());assertEquals(session,v.session().toString());assertTrue(v.setting("lock",false));assertTrue(v.contact(peerId).getBoolean("blocked"));for(JSONObject m:v.messages(null))if(m.optBoolean("outgoing")&&!"pending".equals(m.optString("state")))assertEquals("archived",m.getString("state"));assertEquals(0,Repository.call(v,"syncInbox",new JSONObject()).getJSONArray("messages").length());return true;});
    }
    @Test public void corruptWrongPasswordCancelledAndInvalidSignedArchivesLeaveHistoryUnchanged()throws Exception{
        File archive=file(),invalid=file();local(v->{v.exportMessages(archive,key,salt,()->{});v.clearHistory(null);String unchanged=v.messages(null).toString();byte[] wrong=key.clone();wrong[0]^=1;assertThrows(Exception.class,()->v.restoreMessages(archive,wrong,()->{}));
            try(RandomAccessFile f=new RandomAccessFile(archive,"rw")){long last=f.length()-1;f.seek(last);int previous=f.read();f.seek(last);f.write(previous^1);}assertThrows(Exception.class,()->v.restoreMessages(archive,key,()->{}));assertEquals(unchanged,v.messages(null).toString());
            JSONObject good=v.messages(null).get(0);JSONObject bad=new JSONObject(good.toString());
            // Correctly signed by this phone but semantically invalid: the first row must also roll back.
            JSONObject fresh=v.crypto().encrypt(owner,v.identity(),v.contact(peerId),"Must roll back");good=new JSONObject().put("peer",peerId).put("body","Must roll back").put("envelope",fresh).put("outgoing",true).put("state","sent");JSONObject first=good;
            try(OutputStream out=new FileOutputStream(invalid)){BackupCodec.write(out,key,salt,owner,v.identity().getString("fingerprint"),consumer->{consumer.accept(first.toString().getBytes(Crypto.UTF8));consumer.accept(bad.toString().getBytes(Crypto.UTF8));},v.crypto()::signBackup,()->{});}
            assertThrows(Exception.class,()->v.restoreMessages(invalid,key,()->{}));assertEquals(unchanged,v.messages(null).toString());
            assertThrows(Exception.class,()->v.restoreMessages(invalid,key,()->{throw new IOException("cancelled");}));assertEquals(unchanged,v.messages(null).toString());return true;});
    }
    @Test public void appUsesOnlyAChosenProviderFileAndSurvivesPickerReturnAndRotation()throws Exception{
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,"priperi-qa-"+UUID.randomUUID()+".priperi");values.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");Uri document=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(document);
        Instrumentation.ActivityMonitor save=instrumentation.addMonitor(pickerFilter(Intent.ACTION_CREATE_DOCUMENT),new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(document)),true);
        Instrumentation.ActivityMonitor open=instrumentation.addMonitor(pickerFilter(Intent.ACTION_OPEN_DOCUMENT),new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
        try(ActivityScenario<BackupActivity> scenario=ActivityScenario.launch(BackupActivity.class)){
            await(scenario,a->a.root.isShown());scenario.onActivity(a->find(a.root,"Create encrypted backup").performClick());setSystemPassword(login);clickSystem("Continue");
            await(scenario,a->dialog(a)!=null&&dialog(a).isShowing());scenario.onActivity(a->fillBackup(a,password,true));scenario.recreate();
            await(scenario,a->contains(a.root,"Backup saved."));assertEquals(1,save.getHits());try(InputStream in=context.getContentResolver().openInputStream(document)){assertNotNull(in);assertArrayEquals("PRIPBK01".getBytes(Crypto.UTF8),in.readNBytes(8));}
            local(v->{v.clearHistory(null);return true;});scenario.recreate();await(scenario,a->a.root.isShown());scenario.onActivity(a->find(a.root,"Restore saved messages").performClick());await(scenario,a->contains(a.root,"Backup cancelled."));assertEquals(1,open.getHits());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.onActivity(a->{a.onActivityResult(92,Activity.RESULT_OK,new Intent().setData(document));assertTrue(dialog(a)==null||!dialog(a).isShowing());});scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);await(scenario,a->dialog(a)!=null&&dialog(a).isShowing());scenario.onActivity(a->fillBackup(a,password,false));await(scenario,a->contains(a.root,"Restored 3 saved messages"));assertEquals(1,open.getHits());local(v->{assertEquals(4,v.messages(null).size());return true;});
        }finally{instrumentation.removeMonitor(save);instrumentation.removeMonitor(open);context.getContentResolver().delete(document,null,null);}
    }
    @Test public void usernamesRefreshOnAnotherAccountWithoutChangingPinnedKeysOrApproval()throws Exception{
        local(v->{Session.restore(peer);Repository.send(peer,owner,"Hello before rename");Repository.sync(peer);Session.restore(v);Repository.sync(v);String fingerprint=v.contact(peerId).getString("fingerprint");Session.restore(peer);Repository.call(peer,"renameAccount",new JSONObject().put("username","qa_bkname_"+UUID.randomUUID().toString().substring(0,8)));String newName=Repository.call(peer,"getAccount",new JSONObject()).getString("username");Session.restore(v);Repository.sync(v);assertEquals(newName,v.contact(peerId).getString("username"));assertEquals(fingerprint,v.contact(peerId).getString("fingerprint"));assertTrue(v.contact(peerId).getJSONObject("contactState").getBoolean("incomingPending"));return true;});
    }
    private static IntentFilter pickerFilter(String action)throws Exception{IntentFilter filter=new IntentFilter(action);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");return filter;}
    private static androidx.appcompat.app.AlertDialog dialog(BackupActivity a){try{java.lang.reflect.Field f=BackupActivity.class.getDeclaredField("passwordDialog");f.setAccessible(true);return (androidx.appcompat.app.AlertDialog)f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private static void fillBackup(BackupActivity a,String value,boolean repeat){try{java.lang.reflect.Field first=BackupActivity.class.getDeclaredField("first"),second=BackupActivity.class.getDeclaredField("second");first.setAccessible(true);second.setAccessible(true);((EditText)first.get(a)).setText(value);if(repeat)((EditText)second.get(a)).setText(value);dialog(a).getButton(-1).performClick();}catch(Exception e){throw new AssertionError(e);}}
    private static void setSystemPassword(String text){for(int i=0;i<100;i++){AccessibilityNodeInfo input=editable(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());if(input!=null){Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));return;}SystemClock.sleep(100);}fail("Password prompt missing");}
    private static void clickSystem(String text){for(int i=0;i<100;i++){AccessibilityNodeInfo button=button(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);if(button!=null){assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));return;}SystemClock.sleep(100);}fail("Button missing: "+text);}
    private static AccessibilityNodeInfo editable(AccessibilityNodeInfo n){if(n==null)return null;if(n.isEditable())return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=editable(n.getChild(i));if(c!=null)return c;}return null;}
    private static AccessibilityNodeInfo button(AccessibilityNodeInfo n,String text){if(n==null)return null;if(n.isClickable()&&text.equalsIgnoreCase(String.valueOf(n.getText())))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=button(n.getChild(i),text);if(c!=null)return c;}return null;}
    private interface Ready{boolean test(BackupActivity a);}private static void await(ActivityScenario<BackupActivity> s,Ready r){for(int i=0;i<200;i++){boolean[] ok={false};s.onActivity(a->ok[0]=r.test(a));if(ok[0])return;SystemClock.sleep(100);}s.onActivity(a->System.out.println("BACKUP_UI="+texts(a.root)));fail("Backup screen did not complete");}
    private static String texts(View v){String out=v instanceof TextView&&!(v instanceof EditText)?((TextView)v).getText().toString()+" | ":"";if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)out+=texts(g.getChildAt(i));}return out;}
    private static boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;}return false;}
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View f=find(g.getChildAt(i),text);if(f!=null)return f;}}return null;}
}
