package com.priperi;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeView;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ContactCodeFlowTest {
    private Context context;private Vault peer;private String uid,payload,name;
    private void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){failure[0]=e;return false;}},ok->done.countDown(),e->{failure[0]=new AssertionError(e);done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));if(failure[0]!=null)throw new AssertionError(failure[0]);}
    @Before public void setup()throws Exception{
        org.junit.Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("qr")));
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).commit();Repository.reset();
        local(v->{if(v.account()!=null)throw new SecurityException("Disposable empty installation required");Session.continueAccount(v,"qa_qr_"+UUID.randomUUID().toString().substring(0,8),"Qr-Test-Password-2026!");return true;});
        peer=new Vault(context,"qr_peer_"+UUID.randomUUID());name="qa_qrp_"+UUID.randomUUID().toString().substring(0,8);
        local(v->{Session.continueAccount(peer,name,"Qr-Test-Password-2026!");uid=Session.uid();payload=ContactCode.encode(uid,peer.identity().getString("fingerprint"));Session.restore(v);return true;});
    }
    @After public void cleanup()throws Exception{if(peer==null)return;local(v->{Session.restore(peer);Session.resetAccount(peer);Session.restore(v);Session.resetAccount(v);Repository.eraseLocal(v);return true;});peer.close();Repository.reset();}
    @Test public void codeUsesStableIdentityAndRejectsWrongKeysAndBlocks()throws Exception{
        local(v->{ContactCode code=ContactCode.parse(payload);JSONObject found=Repository.resolveCode(v,code);assertEquals(uid,found.getString("uid"));assertNull(v.contact(uid));
            Session.restore(peer);Repository.call(peer,"renameAccount",new JSONObject().put("username",name+"_x"));Session.restore(v);
            assertEquals(name+"_x",Repository.resolveCode(v,code).getString("username"));
            assertThrows(SecurityException.class,()->Repository.resolveCode(v,ContactCode.parse(ContactCode.encode(uid,"0".repeat(64)))));
            TestIdentityTrust.pin(v,found);v.contactFlag(uid,"blocked",true);assertThrows(SecurityException.class,()->Repository.resolveCode(v,code));return true;});
    }
    @Test public void ownCodeDecodesAndCameraDenialAndLifecycleRemainUsable()throws Exception{
        try(ActivityScenario<ContactCodeActivity> scenario=ActivityScenario.launch(ContactCodeActivity.class)){
            await(scenario,a->{ImageView image=(ImageView)findDescription(a.root,"Your Priperi contact QR code");return a.root.isShown()&&image.getDrawable()!=null;});
            scenario.onActivity(a->{try{ImageView image=(ImageView)findDescription(a.root,"Your Priperi contact QR code");Bitmap bitmap=((BitmapDrawable)image.getDrawable()).getBitmap();int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(),bitmap.getHeight(),pixels))),java.util.Collections.singletonMap(DecodeHintType.ALSO_INVERTED,Boolean.TRUE)).getText();assertEquals(Session.uid(),ContactCode.parse(decoded).uid);assertEquals(0,a.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);}catch(Exception e){throw new AssertionError(e);}});
            // Permission cancellation must leave a usable private screen, with no camera running.
            scenario.moveToState(Lifecycle.State.CREATED);scenario.onActivity(a->a.onRequestPermissionsResult(84,new String[]{android.Manifest.permission.CAMERA},new int[]{PackageManager.PERMISSION_DENIED}));scenario.moveToState(Lifecycle.State.RESUMED);
            await(scenario,a->a.root.isShown());scenario.onActivity(a->assertNull(findCamera(a.root)));
            scenario.recreate();await(scenario,a->a.root.isShown());
        }
    }
    @Test public void lightAndInvertedDarkCodesDecodeWithTheActualScanner()throws Exception{
        for(int[] colors:new int[][]{{0xff122e33,0xffe2f1ed},{0xfff4f8fa,0xff11171d}}){Bitmap bitmap=ContactCodeActivity.bitmap(payload,colors[0],colors[1]);try{int width=bitmap.getWidth(),height=bitmap.getHeight();int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);assertEquals(colors[1],pixels[0]);com.journeyapps.barcodescanner.Decoder decoder=ContactCodeActivity.scannerDecoder().createDecoder(java.util.Collections.emptyMap());Result result=null;for(int i=0;i<4&&result==null;i++)result=decoder.decode(new RGBLuminanceSource(width,height,pixels));assertNotNull("The mixed scanner must read each theme",result);assertEquals(payload,result.getText());}finally{bitmap.recycle();}}
    }
    @Test public void scanRequiresConfirmationBeforePinAndCanOpenChat()throws Exception{
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.CAMERA);
        try(ActivityScenario<ContactCodeActivity> scenario=ActivityScenario.launch(ContactCodeActivity.class)){
            await(scenario,a->a.root.isShown());
            // Exercise the in-app scanner callback with decoded synthetic payloads.
            scenario.onActivity(a->{findText(a.root,"Scan a code").performClick();a.scanned("https://invalid.example");assertNotNull(findText(scannerRoot(a),"This is not a Priperi contact code."));a.scanned(payload);});
            await(scenario,a->{try{return dialog(a)!=null&&dialog(a).isShowing();}catch(Exception e){return false;}});
            scenario.onActivity(a->{try{androidx.appcompat.app.AlertDialog first=dialog(a);a.scanned(payload);assertSame("A repeated camera frame reopened the prompt",first,dialog(a));first.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();}catch(Exception e){throw new AssertionError(e);}});
            await(scenario,a->{try{return dialog(a)!=null&&!dialog(a).isShowing()&&scannerRoot(a)==a.root;}catch(Exception e){return false;}});
            scenario.onActivity(a->{findText(a.root,"Scan a code").performClick();a.scanned(payload);});
            await(scenario,a->{try{return dialog(a)!=null&&dialog(a).isShowing();}catch(Exception e){return false;}});
            local(v->{assertNull(v.contact(uid));return true;});
            scenario.onActivity(a->{try{dialog(a).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();}catch(Exception e){throw new AssertionError(e);}});
            for(int i=0;i<100&&scenario.getState()!=Lifecycle.State.DESTROYED;i++)SystemClock.sleep(100);
            assertEquals(Lifecycle.State.DESTROYED,scenario.getState());local(v->{assertNotNull(v.contact(uid));assertTrue(v.contact(uid).getBoolean("verified"));return true;});
        }
    }
    private static View scannerRoot(ContactCodeActivity a){try{java.lang.reflect.Field f=ContactCodeActivity.class.getDeclaredField("cameraDialog");f.setAccessible(true);androidx.appcompat.app.AlertDialog d=(androidx.appcompat.app.AlertDialog)f.get(a);return d==null?a.root:d.getWindow().getDecorView();}catch(Exception e){throw new AssertionError(e);}}
    private static androidx.appcompat.app.AlertDialog dialog(ContactCodeActivity a)throws Exception{java.lang.reflect.Field f=ContactCodeActivity.class.getDeclaredField("confirmation");f.setAccessible(true);return (androidx.appcompat.app.AlertDialog)f.get(a);}
    private interface Ready{boolean test(ContactCodeActivity a);}
    private static void await(ActivityScenario<ContactCodeActivity> s,Ready r){for(int i=0;i<100;i++){boolean[] ok={false};s.onActivity(a->ok[0]=r.test(a));if(ok[0])return;SystemClock.sleep(100);}fail("QR screen did not become usable");}
    private static View findText(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return v;return children(v,c->findText(c,s));}
    private static View findDescription(View v,String s){if(s.equals(v.getContentDescription()))return v;return children(v,c->findDescription(c,s));}
    private static View findCamera(View v){if(v instanceof BarcodeView)return v;return children(v,ContactCodeFlowTest::findCamera);}
    private interface Find{View get(View v);}private static View children(View v,Find f){if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=f.get(g.getChildAt(i));if(found!=null)return found;}}return null;}
}
