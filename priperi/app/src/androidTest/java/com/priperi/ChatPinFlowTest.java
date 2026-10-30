package com.priperi;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class) public class ChatPinFlowTest {
    private Context context;private Vault peer;private String peerId,groupId;
    private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> done=new CompletableFuture<>();Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){done.completeExceptionally(e);return null;}},done::complete,e->done.completeExceptionally(new AssertionError(e)));return done.get(40,TimeUnit.SECONDS);}
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));Repository.reset();
        context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();String suffix=UUID.randomUUID().toString().substring(0,8);groupId=UUID.randomUUID().toString();peer=new Vault(context,"qa_pin_peer_"+suffix);
        local(v->{assertNull(v.account());Session.continueAccount(v,"qa_pin_"+suffix,"Pin-Test-Password-2026!");peerId=Session.continueAccount(peer,"qa_pinpeer_"+suffix,"Pin-Test-Password-2026!").getString("uid");Session.restore(v);v.pin(Repository.call(v,"lookupUser",new JSONObject().put("uid",peerId)));GroupRepository.create(v,groupId,"Pinned team");Repository.sync(v);return true;});
    }
    @After public void cleanup()throws Exception{local(v->{Session.restore(peer);Session.resetAccount(peer);Session.restore(v);Session.resetAccount(v);Repository.eraseLocal(v);return true;});peer.destroy();Repository.reset();}
    @Test public void directAndGroupPinsPersistAcrossReloadAndProfileRefresh()throws Exception{
        local(v->{assertTrue(v.pinnedChats().isEmpty());v.setChatPinned(peerId,true);v.setChatPinned("group:"+groupId,true);v.pin(Repository.call(v,"lookupUser",new JSONObject().put("uid",peerId)));Repository.sync(v);assertEquals(Set.of(peerId,"group:"+groupId),v.pinnedChats());return true;});Repository.reset();
        local(v->{assertEquals(Set.of(peerId,"group:"+groupId),v.pinnedChats());v.setChatPinned(peerId,false);assertEquals(Set.of("group:"+groupId),v.pinnedChats());assertThrows(SecurityException.class,()->v.setChatPinned(UUID.randomUUID().toString(),true));v.setChatPinned("group:"+groupId,false);return true;});Repository.reset();assertTrue(local(v->v.pinnedChats().isEmpty()));
    }
    @Test public void longPressPinsGroupAboveNewerDirectMessageAndUnpins()throws Exception{
        local(v->{Repository.send(v,peerId,"A newer direct conversation");return true;});
        try(ActivityScenario<InboxActivity> screen=ActivityScenario.launch(InboxActivity.class)){
            await(screen,a->list(a.root)!=null&&list(a.root).getCount()==2);
            screen.onActivity(a->{ListView list=list(a.root);View row=list.getChildAt(1);assertTrue(row.getContentDescription().toString().startsWith("Pinned team."));assertTrue(list.getOnItemLongClickListener().onItemLongClick(list,row,1,1));});tap("Pin chat");
            await(screen,a->{ListView l=list(a.root);return l!=null&&l.getChildAt(0)!=null&&l.getChildAt(0).getContentDescription().toString().startsWith("Pinned team. Pinned chat");});assertTrue(local(v->v.pinnedChats().contains("group:"+groupId)));
            screen.recreate();await(screen,a->{ListView l=list(a.root);return l!=null&&l.getChildAt(0)!=null&&l.getChildAt(0).getContentDescription().toString().startsWith("Pinned team. Pinned chat");});
            screen.onActivity(a->{ListView l=list(a.root);l.getOnItemLongClickListener().onItemLongClick(l,l.getChildAt(0),0,0);});tap("Unpin chat");
            await(screen,a->{ListView l=list(a.root);return l!=null&&l.getChildAt(0)!=null&&!l.getChildAt(0).getContentDescription().toString().startsWith("Pinned team.");});assertTrue(local(v->v.pinnedChats().isEmpty()));
        }
    }
    private static void tap(String label){
        android.app.Instrumentation inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        for(int i=0;i<100;i++){android.view.accessibility.AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText()==null?"":node.getText())){android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);if(!bounds.isEmpty()){long now=SystemClock.uptimeMillis();inst.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0));inst.sendPointerSync(MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0));inst.waitForIdleSync();return;}}SystemClock.sleep(50);}fail("Missing pin action: "+label);
    }
    private interface Ready{boolean check(InboxActivity a);}
    private static void await(ActivityScenario<InboxActivity> screen,Ready ready){for(int i=0;i<150;i++){boolean[] ok={false};screen.onActivity(a->ok[0]=a.root.isShown()&&ready.check(a));if(ok[0])return;SystemClock.sleep(50);}fail("Pinned chat list did not update");}
    private static ListView list(View view){if(view instanceof ListView)return (ListView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ListView found=list(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
}
