package com.priperi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.FileInputStream;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Regression checks for permission-return and a busy account worker. Local fixture only. */
@RunWith(AndroidJUnit4.class)
public class NotificationRecoveryTest {
    private Context context;
    private void local(Repository.Job<Boolean> job) throws Exception {
        CountDownLatch done=new CountDownLatch(1);String[] error={null};
        Repository.local(context,v->{try{return job.run(v);}catch(AssertionError failure){error[0]=failure.toString();return false;}},ok->done.countDown(),message->{error[0]=message;done.countDown();});
        assertTrue(done.await(30,TimeUnit.SECONDS));assertNull(error[0]);
    }
    @Before public void setup() throws Exception {
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        Repository.reset();local(v->{
            if(v.account()!=null){if(!v.account().optString("username").startsWith("qa_notice_"))throw new SecurityException("Disposable installation required");Session.resetAccount(v);Repository.eraseLocal(v);}return true;
        });local(v->{Session.continueAccount(v,"qa_notice_"+UUID.randomUUID().toString().substring(0,8),"Notice-Password-2026!");return true;});
    }
    @After public void cleanup() throws Exception {
        local(v->{if(v.account()!=null)Session.resetAccount(v);Repository.eraseLocal(v);return true;});Repository.reset();
    }
    @Test public void resumingBehindSlowWorkShowsGateAndNeverRevealsChatsEarly() throws Exception {
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)) {
            awaitVisible(scenario,"Appearance");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            try {
                Repository.work(context,v->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return true;},null,null);
                assertTrue(entered.await(5,TimeUnit.SECONDS));scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);
                SystemClock.sleep(250);scenario.onActivity(a->{assertTrue(find(a.getWindow().getDecorView(),"Opening Priperi…").isShown());assertFalse(find(a.getWindow().getDecorView(),"Appearance").isShown());});
            } finally {release.countDown();}
            awaitVisible(scenario,"Appearance");
            scenario.recreate();awaitVisible(scenario,"Appearance");
        }
    }
    @Test public void permissionCancelAndEnableReturnToUsableSettings() throws Exception {
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)) {
            awaitVisible(scenario,"Appearance");
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(a->a.onRequestPermissionsResult(49,new String[]{Manifest.permission.POST_NOTIFICATIONS},new int[]{PackageManager.PERMISSION_DENIED}));
            scenario.moveToState(Lifecycle.State.RESUMED);awaitVisible(scenario,"Appearance");
            shell("pm grant "+context.getPackageName()+" android.permission.POST_NOTIFICATIONS");
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(a->a.onRequestPermissionsResult(49,new String[]{Manifest.permission.POST_NOTIFICATIONS},new int[]{PackageManager.PERMISSION_GRANTED}));
            scenario.moveToState(Lifecycle.State.RESUMED);awaitVisible(scenario,"Appearance");
            // A token fetch must not occupy Repository's one vault worker.
            long started=SystemClock.elapsedRealtime();local(v->true);
            assertTrue("FCM token setup blocked the account worker",SystemClock.elapsedRealtime()-started<5000);
            scenario.onActivity(a->assertTrue(find(a.getWindow().getDecorView(),"Appearance").isShown()));
        }
    }
    @Test public void overduePolicyFailureKeepsContentHiddenAndRetryRecovers() throws Exception {
        Assume.assumeTrue("Requires isolated delay fixture", "yes".equals(InstrumentationRegistry.getArguments().getString("delayFixture")));
        local(v->{v.accountPolicy(new org.json.JSONObject().put("days",1).put("serverTime",System.currentTimeMillis()).put("lastActive",0));return true;});
        Session.request("http://10.0.2.2:5001/qa/policy-error",new org.json.JSONObject(),null,false);
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)) {
            boolean problem=false;for(int i=0;i<100;i++){boolean[] ready={false};scenario.onActivity(a->{assertFalse("Expired-policy content appeared before a successful check",a.root.isShown());View retry=find(a.getWindow().getDecorView(),"Try again");ready[0]=retry!=null&&retry.isShown();});if(ready[0]){problem=true;break;}SystemClock.sleep(100);}assertTrue("Expected recoverable policy error",problem);
            scenario.onActivity(a->{assertFalse(a.root.isShown());find(a.getWindow().getDecorView(),"Try again").performClick();});
            awaitVisible(scenario,"Appearance");local(v->{assertFalse(v.policyNeedsCheck());return true;});
        }
    }
    @Test public void explicitOffThenOnControlsSurviveDenialAndGuideToPhoneSettings()throws Exception{
        local(v->{v.setSetting("notifications",true);return true;});
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)){
            awaitVisible(scenario,"Appearance");scenario.onActivity(a->{assertNull(find(a.root,"Turn on"));assertNull(find(a.root,"Turn off"));tap(find(a.root,"Notifications"));});
            chooseNotification("Off");
            for(int i=0;i<100;i++){boolean[] off={false};scenario.onActivity(a->{View row=find(a.root,"Off");off[0]=row!=null;});if(off[0])break;SystemClock.sleep(50);}
            local(v->{assertFalse(v.setting("notifications",true));return true;});
            scenario.onActivity(a->a.onRequestPermissionsResult(49,new String[]{Manifest.permission.POST_NOTIFICATIONS},new int[]{PackageManager.PERMISSION_DENIED}));
            scenario.recreate();awaitVisible(scenario,"Appearance");scenario.onActivity(a->tap(find(a.root,"Notifications")));chooseNotification("On");
            AccountControlsFlowTest.click("Cancel");awaitVisible(scenario,"Appearance");
        }
    }
    private static void chooseNotification(String label)throws Exception{for(int i=0;i<100;i++){android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText())){android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);if(!bounds.isEmpty()){long at=SystemClock.uptimeMillis();InstrumentationRegistry.getInstrumentation().sendPointerSync(android.view.MotionEvent.obtain(at,at,android.view.MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0));InstrumentationRegistry.getInstrumentation().sendPointerSync(android.view.MotionEvent.obtain(at,at+60,android.view.MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0));InstrumentationRegistry.getInstrumentation().waitForIdleSync();return;}}SystemClock.sleep(50);}fail("Missing notification choice: "+label);}

    private static void tap(View view){assertNotNull(view);while(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(view.performClick());}
    private static void shell(String command)throws Exception {
        try(ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);FileInputStream input=new FileInputStream(fd.getFileDescriptor())){byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){}}
    }
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View child=find(g.getChildAt(i),text);if(child!=null)return child;}}return null;}
    private static void describe(View v,StringBuilder out){if(v instanceof TextView)out.append(((TextView)v).getText()).append(":").append(v.isShown()).append(";");if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)describe(((ViewGroup)v).getChildAt(i),out);}
    private static void awaitVisible(ActivityScenario<SettingsActivity> scenario,String text){for(int i=0;i<100;i++){boolean[] ready={false};scenario.onActivity(a->{View v=find(a.getWindow().getDecorView(),text);ready[0]=v!=null&&v.isShown();});if(ready[0])return;SystemClock.sleep(100);}StringBuilder details=new StringBuilder();scenario.onActivity(a->describe(a.getWindow().getDecorView(),details));fail("Settings stayed hidden: "+text+" actual="+details);}
}
