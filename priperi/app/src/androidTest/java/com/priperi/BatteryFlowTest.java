package com.priperi;
import android.content.*;import android.net.*;import android.os.*;
import androidx.test.core.app.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import java.util.*;import java.util.concurrent.*;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class BatteryFlowTest {
 Context context;
 void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);String[] error={null};Repository.local(context,job,r->done.countDown(),e->{error[0]=e;done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));assertNull(error[0]);}
 static void shell(String cmd)throws Exception{try(ParcelFileDescriptor p=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);java.io.FileInputStream in=new java.io.FileInputStream(p.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
 static Object field(String name)throws Exception{java.lang.reflect.Field f=SyncCoordinator.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static int starts(){int[] value={0};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{Object budget=field("budget");java.lang.reflect.Field f=SyncBudget.class.getDeclaredField("starts");f.setAccessible(true);value[0]=((java.util.ArrayDeque<?>)f.get(budget)).size();}catch(Exception e){throw new AssertionError(e);}});return value[0];}
 boolean online(){ConnectivityManager m=context.getSystemService(ConnectivityManager.class);NetworkCapabilities c=m.getNetworkCapabilities(m.getActiveNetwork());return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}
 @Test public void lateLossOfAnOldNetworkKeepsTheCurrentNetworkPolling()throws Exception{
  Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("optimisation")));context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();local(v->{assertNull(v.account());Session.continueAccount(v,"qa_handover_"+UUID.randomUUID().toString().substring(0,8),"Battery-Test-2026!");return true;});
  try(ActivityScenario<InboxActivity> screen=ActivityScenario.launch(InboxActivity.class)){
   Handler main=(Handler)field("MAIN");Runnable poll=(Runnable)field("poll");boolean ready=false;
   for(int i=0;i<150;i++){if(online()&&!(Boolean)field("running")&&main.hasCallbacks(poll)){ready=true;break;}SystemClock.sleep(30);}assertTrue("Polling did not become ready",ready);
   ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network active=manager.getActiveNetwork(),old=Network.fromNetworkHandle(active.getNetworkHandle()+0x100000000L);assertNotEquals(active,old);
   ((ConnectivityManager.NetworkCallback)field("network")).onLost(old);InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertTrue("Current network disappeared",online());assertTrue("Old network loss incorrectly marked the app offline",(Boolean)field("online"));assertTrue("Old network loss cancelled current-network polling",main.hasCallbacks(poll));
  }finally{local(v->{Session.resetAccount(v);Repository.eraseLocal(v);return true;});Repository.reset();}
 }
 @Test public void offlinePollingStopsAndReconnectWakesWithoutANewPermission()throws Exception{
  Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("optimisation")));context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();local(v->{if(v.account()!=null)throw new SecurityException("Disposable installation required");Session.continueAccount(v,"qa_bat_"+UUID.randomUUID().toString().substring(0,8),"Battery-Test-2026!");return true;});
  try(ActivityScenario<InboxActivity> s=ActivityScenario.launch(InboxActivity.class)){
   for(int i=0;i<100;i++){boolean[] ready={false};s.onActivity(a->ready[0]=a.root.isShown());if(ready[0])break;SystemClock.sleep(100);}local(v->true);
   try{shell("svc wifi disable");shell("svc data disable");for(int i=0;i<100&&online();i++)SystemClock.sleep(100);assertFalse(online());SystemClock.sleep(1500);int before=starts();SystemClock.sleep(8500);assertEquals("Offline sync started network work",before,starts());shell("svc wifi enable");shell("svc data enable");for(int i=0;i<150&&starts()<=before;i++)SystemClock.sleep(100);assertTrue("Reconnect did not wake sync",starts()>before);System.out.println("OFFLINE_SYNC startsDuring8_5s=0 reconnect=immediate");}
   finally{shell("svc wifi enable");shell("svc data enable");}
  }finally{local(v->{Session.resetAccount(v);Repository.eraseLocal(v);return true;});Repository.reset();}
 }
}
