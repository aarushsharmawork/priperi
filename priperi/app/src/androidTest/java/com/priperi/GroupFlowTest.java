package com.priperi;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
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

@RunWith(AndroidJUnit4.class)
public class GroupFlowTest {
    private Context context;private Vault bob,eve;private String aliceId,bobId,eveId,gid,invite,bobScope;
    private void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){error[0]=e;return false;}},ok->done.countDown(),e->{error[0]=new AssertionError(e);done.countDown();});assertTrue(done.await(60,TimeUnit.SECONDS));if(error[0]!=null)throw new AssertionError(error[0]);}
    @Before public void setup()throws Exception {
        Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("groups")));context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).commit();Repository.reset();String suffix=UUID.randomUUID().toString().substring(0,8);bobScope="groups_bob_"+suffix;bob=new Vault(context,bobScope);eve=new Vault(context,"groups_eve_"+suffix);gid=UUID.randomUUID().toString();invite=UUID.randomUUID().toString();
        local(v->{if(v.account()!=null)throw new SecurityException("Disposable installation required");aliceId=Session.continueAccount(v,"qa_ga_"+suffix,"Group-Password-2026!").getString("uid");bobId=Session.continueAccount(bob,"qa_gb_"+suffix,"Group-Password-2026!").getString("uid");eveId=Session.continueAccount(eve,"qa_ge_"+suffix,"Group-Password-2026!").getString("uid");Session.restore(v);GroupRepository.create(v,gid,"Weekend plans");GroupRepository.action(v,"inviteGroupMember",new JSONObject().put("groupId",gid).put("uid",bobId).put("id",invite));Repository.sync(v);return true;});
    }
    @After public void cleanup()throws Exception {if(context==null)return;local(v->{Session.restore(eve);if(eve.account()!=null)Session.resetAccount(eve);Session.restore(bob);if(bob.account()!=null)Session.resetAccount(bob);Session.restore(v);if(v.account()!=null){Session.resetAccount(v);Repository.eraseLocal(v);}return true;});bob.destroy();eve.destroy();Repository.reset();}
    @Test public void encryptedFanoutStaleMembershipAndBackupPreserveExistingPrivateKeys()throws Exception {
        local(v->{
            Session.restore(bob);Repository.sync(bob);assertEquals(0,bob.groups().size());assertEquals(1,bob.groupInvites().length());GroupRepository.action(bob,"acceptGroupInvite",new JSONObject().put("id",invite));Session.restore(v);Repository.sync(v);
            byte[] idleBefore=metadata(v);Repository.sync(v);Repository.sync(v);assertArrayEquals("Unchanged sync rewrote private metadata",idleBefore,metadata(v));
            JSONObject outgoing=TestIdentityTrust.sendGroup(v,gid,"Only accepted members can read this.");JSONArray copies=outgoing.getJSONArray("groupDeliveries");assertEquals(1,copies.length());Repository.sync(v);assertEquals("sent",v.message(outgoing.getJSONObject("envelope").getString("id")).optString("state"));assertFalse(v.message(outgoing.getJSONObject("envelope").getString("id")).has("groupDeliveries"));
            Session.restore(bob);Repository.sync(bob);assertEquals("Only accepted members can read this.",bob.messages("group:"+gid).get(0).getString("body"));assertNull(bob.contact(aliceId));
            JSONObject received=bob.messages("group:"+gid).get(0),sender=received.getJSONObject("author"),bad=new JSONObject(received.getJSONObject("envelope").toString()).put("group",UUID.randomUUID().toString());assertThrows(java.security.GeneralSecurityException.class,()->bob.crypto().decrypt(bobId,bob.identity(),sender,bad));Repository.sync(bob);assertEquals(1,bob.messages("group:"+gid).size());
            Session.restore(eve);Repository.sync(eve);assertTrue(eve.groups().isEmpty());assertTrue(eve.messages(null).isEmpty());Session.restore(v);
            String secondInvite=UUID.randomUUID().toString();GroupRepository.action(v,"inviteGroupMember",new JSONObject().put("groupId",gid).put("uid",eveId).put("id",secondInvite));JSONObject stale=TestIdentityTrust.sendGroup(v,gid,"A draft for the previous membership");Session.restore(eve);GroupRepository.action(eve,"acceptGroupInvite",new JSONObject().put("id",secondInvite));Session.restore(v);assertTrue(Repository.sync(v).contains("group changed"));assertEquals("group-changed",v.message(stale.getJSONObject("envelope").getString("id")).optString("state"));
            JSONObject fanout=TestIdentityTrust.sendGroup(v,gid,"Hello to both members");assertEquals(2,fanout.getJSONArray("groupDeliveries").length());assertNotEquals(fanout.getJSONArray("groupDeliveries").getJSONObject(0).getString("ciphertext"),fanout.getJSONArray("groupDeliveries").getJSONObject(1).getString("ciphertext"));Repository.sync(v);Session.restore(eve);Repository.sync(eve);assertEquals(1,eve.messages("group:"+gid).size());Session.restore(bob);Repository.sync(bob);assertEquals(2,bob.messages("group:"+gid).size());
            byte[] key=new byte[32],salt=new byte[16];new java.security.SecureRandom().nextBytes(key);new java.security.SecureRandom().nextBytes(salt);File backup=new File(context.getCacheDir(),"group-test.enc");String identity=bob.identity().toString(),session=bob.session().toString();try{assertEquals(2,bob.exportMessages(backup,key,salt,()->{}));bob.clearHistory("group:"+gid);assertEquals(2,bob.restoreMessages(backup,key,()->{}));assertEquals(0,bob.restoreMessages(backup,key,()->{}));assertEquals(identity,bob.identity().toString());assertEquals(session,bob.session().toString());}finally{backup.delete();Arrays.fill(key,(byte)0);}
            Session.restore(v);File ownBackup=new File(context.getCacheDir(),"group-own-test.enc");try{assertEquals(2,v.exportMessages(ownBackup,new byte[32],new byte[16],()->{}));}finally{ownBackup.delete();}
            GroupRepository.action(v,"removeGroupMember",new JSONObject().put("groupId",gid).put("uid",bobId));Session.restore(bob);byte[] beforeRemoval=metadata(bob);Repository.sync(bob);assertFalse(Arrays.equals(beforeRemoval,metadata(bob)));bob.close();bob=new Vault(context,bobScope);Session.restore(bob);assertFalse(bob.group(gid).optBoolean("active"));assertThrows(SecurityException.class,()->TestIdentityTrust.sendGroup(bob,gid,"Removed member"));Session.restore(v);return true;
        });
    }
    @Test public void groupEditReplyAndDeleteUseExistingEncryptedRelay()throws Exception {
        local(v->{Session.restore(bob);Repository.sync(bob);GroupRepository.action(bob,"acceptGroupInvite",new JSONObject().put("id",invite));Session.restore(v);Repository.sync(v);
            JSONObject root=TestIdentityTrust.sendGroup(v,gid,"Group original");String id=root.getJSONObject("envelope").getString("id");Repository.sync(v);Session.restore(bob);Repository.sync(bob);assertEquals("Group original",bob.message(id).getString("body"));Session.restore(v);
            TestIdentityTrust.change(v,"group:"+gid,id,"Group edit",false);Repository.sync(v);Session.restore(bob);Repository.sync(bob);assertEquals("Group edit",bob.message(id).getString("body"));JSONObject reply=TestIdentityTrust.send(bob,"group:"+gid,"Group answer",id);String rid=reply.getJSONObject("envelope").getString("id");Repository.sync(bob);Session.restore(v);Repository.sync(v);assertEquals(id,v.message(rid).getString("replyTo"));TestIdentityTrust.change(v,"group:"+gid,id,null,true);Repository.sync(v);Session.restore(bob);Repository.sync(bob);assertTrue(bob.message(id).getBoolean("deleted"));assertEquals("",bob.message(id).getJSONObject("envelope").getString("ciphertext"));Session.restore(v);return true;});
    }
    @Test public void requestsTabRequiresExplicitAcceptanceAndGroupDetailsExposeOnlyAllowedControls()throws Exception {
        local(v->{GroupRepository.action(v,"leaveGroup",new JSONObject().put("groupId",gid));gid=UUID.randomUUID().toString();invite=UUID.randomUUID().toString();Session.restore(bob);GroupRepository.create(bob,gid,"Weekend plans");GroupRepository.action(bob,"inviteGroupMember",new JSONObject().put("groupId",gid).put("uid",aliceId).put("id",invite));Session.restore(v);Repository.sync(v);return true;});
        try(ActivityScenario<InboxActivity> inbox=ActivityScenario.launch(InboxActivity.class)){
            await(inbox,a->a.root.isShown()&&find(a.root,"Groups · 1")!=null);inbox.onActivity(a->{assertNull(find(a.root,"Weekend plans"));tap(find(a.root,"Groups · 1"));});
            await(inbox,a->find(a.root,"Weekend plans")!=null&&find(a.root,"Accept")!=null);inbox.onActivity(a->{save(a,"group-request");find(a.root,"Accept").performClick();});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();inbox.onActivity(a->{try{java.lang.reflect.Field field=InboxActivity.class.getDeclaredField("inviteDialog");field.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field.get(a);assertNotNull(dialog);assertTrue(dialog.isShowing());assertTrue("Accept".equalsIgnoreCase(dialog.getButton(-1).getText().toString()));dialog.getButton(-1).performClick();}catch(Exception e){throw new AssertionError(e);}});await(inbox,a->find(a.root,"Accept")==null&&find(a.root,"Weekend plans")!=null);inbox.onActivity(a->tap(find(a.root,"All chats")));await(inbox,a->find(a.root,"Weekend plans")!=null);inbox.onActivity(a->save(a,"group-inbox"));
        }
        try(ActivityScenario<GroupDetailsActivity> details=ActivityScenario.launch(new Intent(context,GroupDetailsActivity.class).putExtra("group",gid))){await(details,a->a.root.isShown()&&find(a.root,"Weekend plans")!=null);details.onActivity(a->{assertNull(find(a.root,"Add members"));assertNull(find(a.root,"Change group name"));assertNotNull(find(a.root,"Leave group"));save(a,"group-member");});}
        local(v->{Session.restore(bob);GroupRepository.action(bob,"setGroupAdmin",new JSONObject().put("groupId",gid).put("uid",aliceId).put("admin",true));Session.restore(v);Repository.sync(v);return true;});
        try(ActivityScenario<GroupDetailsActivity> details=ActivityScenario.launch(new Intent(context,GroupDetailsActivity.class).putExtra("group",gid))){await(details,a->a.root.isShown()&&find(a.root,"Add members")!=null);details.onActivity(a->{assertNotNull(find(a.root,"Change group name"));save(a,"group-admin");});}
    }
    private static void tap(View view){while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(view.performClick());}
    private static byte[] metadata(Vault vault)throws Exception{java.lang.reflect.Field field=Vault.class.getDeclaredField("directory");field.setAccessible(true);try(InputStream in=new FileInputStream(new File((File)field.get(vault),"identity.bin"))){return Vault.readAll(in);}}
    private static void clickDialog(String text){for(int i=0;i<100;i++){android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(n.getText())&&n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return;SystemClock.sleep(100);}fail("Missing dialog button: "+text);}
    private interface Ready<T extends android.app.Activity>{boolean test(T a);}private static <T extends android.app.Activity>void await(ActivityScenario<T> s,Ready<T> r){for(int i=0;i<160;i++){boolean[] ready={false};s.onActivity(a->ready[0]=r.test(a));if(ready[0])return;SystemClock.sleep(100);}fail("Group UI did not update");}
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static ListView findList(View v){if(v instanceof ListView)return (ListView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ListView list=findList(((ViewGroup)v).getChildAt(i));if(list!=null)return list;}return null;}
    private static void save(android.app.Activity a,String name){try{View view=a.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));File dir=new File(a.getExternalFilesDir(null),"group-previews");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}catch(IOException e){throw new AssertionError(e);}}
}
