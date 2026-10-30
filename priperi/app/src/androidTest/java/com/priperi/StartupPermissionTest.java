package com.priperi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Each phase starts after the fixture host revokes permission, outside this process. */
@RunWith(AndroidJUnit4.class)
public class StartupPermissionTest {
    @Test public void firstLaunchGrantContinuesWithoutBlankScreen() throws Exception { phase("grant",true); }
    @Test public void firstLaunchDenialContinuesAndDoesNotAskAgain() throws Exception { phase("deny",false); }
    private void phase(String name,boolean grant) throws Exception {
        assumeTrue(name.equals(InstrumentationRegistry.getArguments().getString("startupPermission")));
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        Context context=ApplicationProvider.getApplicationContext();
        assertNotEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().clear().commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            long end=SystemClock.elapsedRealtime()+8000;AccessibilityNodeInfo button=null;
            while(button==null&&SystemClock.elapsedRealtime()<end){button=findButton(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),grant);if(button==null)SystemClock.sleep(100);}
            assertNotNull("System notification permission was not requested at startup",button);
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            awaitContinue(scenario);
            assertEquals(grant,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
            assertTrue(context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).getBoolean("asked",false));
            assertEquals(grant,context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).getBoolean("enable_after_sign_in",false));
        }
        try(ActivityScenario<MainActivity> second=ActivityScenario.launch(MainActivity.class)){awaitContinue(second);}
    }
    private static AccessibilityNodeInfo findButton(AccessibilityNodeInfo node,boolean grant){
        if(node==null)return null;String text=node.getText()==null?"":node.getText().toString().toLowerCase(Locale.ROOT);
        if(node.isClickable()&&(grant?text.equals("allow"):text.startsWith("don")&&text.contains("allow")))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=findButton(node.getChild(i),grant);if(child!=null)return child;}return null;
    }
    private static boolean continueVisible(View node){if(node instanceof TextView&&"Continue".contentEquals(((TextView)node).getText())&&node.isShown())return true;if(node instanceof ViewGroup){ViewGroup group=(ViewGroup)node;for(int i=0;i<group.getChildCount();i++)if(continueVisible(group.getChildAt(i)))return true;}return false;}
    private static void awaitContinue(ActivityScenario<MainActivity> scenario){for(int i=0;i<100;i++){boolean[] shown={false};scenario.onActivity(a->shown[0]=continueVisible(a.getWindow().getDecorView()));if(shown[0])return;SystemClock.sleep(100);}fail("Continue stayed hidden after the notification choice");}
}
