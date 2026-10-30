package com.priperi;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class StrictDevicePolicyTest {
    @Test public void ordinaryBuildBlocksEmulatorBeforeAccountAccessAndIgnoresBypassExtras() throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("strict")));
        assertFalse(BuildConfig.ALLOW_TEST_DEVICES);
        assertTrue(DeviceSecurity.isEmulator(Build.FINGERPRINT,Build.MODEL,Build.HARDWARE,Build.PRODUCT,Build.MANUFACTURER));
        Context context=ApplicationProvider.getApplicationContext();assertTrue(DeviceSecurity.blockedReason(context).contains("physical Android phone"));
        Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("authenticated",true).putExtra("allow_test_devices",true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
            for(int i=0;i<50;i++) {boolean[] found={false};scenario.onActivity(a->found[0]=find(a.getWindow().getDecorView(),"Priperi is locked"));if(found[0])break;SystemClock.sleep(100);}
            scenario.onActivity(a->{assertTrue(find(a.getWindow().getDecorView(),"Priperi is locked"));assertFalse(find(a.getWindow().getDecorView(),"Continue"));assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);});
        }
        try(ActivityScenario<SplashActivity> scenario=ActivityScenario.launch(new Intent(context,SplashActivity.class).putExtra("authenticated",true).putExtra("allow_test_devices",true))) {
            SystemClock.sleep(350);scenario.onActivity(a->{assertTrue(find(a.getWindow().getDecorView(),"Priperi is locked"));assertFalse(find(a.getWindow().getDecorView(),"Continue"));});
        }
        try {DeviceSecurity.requireAllowed();fail("Emulator bypassed the network/account guard");}catch(SecurityException expected){assertTrue(expected.getMessage().contains("physical Android phone"));}
    }
    private static boolean find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(find(g.getChildAt(i),text))return true;}return false;}
}
