package com.priperi;
import android.content.*;import android.view.*;import android.widget.*;import android.os.SystemClock;
import androidx.test.core.app.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import androidx.lifecycle.Lifecycle;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import java.util.concurrent.*;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class SecurityBoundaryTest {
 Context context;
 void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);String[] error={null};Repository.local(context,job,r->done.countDown(),e->{error[0]=e;done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));assertNull(error[0]);}
 @Before public void setup()throws Exception{Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("securityAudit")));context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();local(v->{if(v.account()!=null)throw new SecurityException("Disposable empty installation required");String uid="00000000-0000-4000-8000-000000000001";JSONObject i=v.identity();v.account(new JSONObject().put("uid",uid).put("username","qa_security").put("fingerprint",i.getString("fingerprint")).put("hpke",i.getString("hpke")).put("signing",i.getString("signing")));v.session(new JSONObject().put("uid",uid).put("access_token","test-only-token").put("refresh_token","test-refresh").put("expires_at",System.currentTimeMillis()/1000+3600));Session.restore(v);return true;});}
 @After public void cleanup()throws Exception{if(context!=null){local(v->{Repository.eraseLocal(v);return true;});Repository.reset();}}
 @Test public void dialogCannotOutliveThePrivateScreenGate()throws Exception{
  androidx.appcompat.app.AlertDialog[] dialog={null};EditText[] password={null};CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
  try(ActivityScenario<SettingsActivity> s=ActivityScenario.launch(SettingsActivity.class)){
   await(s,a->a.root.isShown()&&find(a.root,"Appearance")!=null);s.onActivity(a->{LinearLayout box=Ui.column(a);password[0]=Ui.input(a,box,"Synthetic password",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password[0].setText("Synthetic-Password-Only!");dialog[0]=Ui.dialog(a).setTitle("Synthetic private data").setView(box).setPositiveButton("Done",null).show();});
   s.moveToState(Lifecycle.State.CREATED);Repository.local(context,v->{held.countDown();release.await(15,TimeUnit.SECONDS);return true;},ok->{},error->{});assertTrue(held.await(10,TimeUnit.SECONDS));s.moveToState(Lifecycle.State.RESUMED);
   try{s.onActivity(a->{assertFalse("Paused dialog remained above the private loading gate",dialog[0].isShowing());assertEquals("Paused password was retained in its input",0,password[0].length());assertFalse(a.root.isShown());});}finally{release.countDown();}await(s,a->a.root.isShown());
  }finally{release.countDown();}
 }
 @Test public void everyDialogGetsItsOwnSecureWindow()throws Exception{try(ActivityScenario<SettingsActivity> s=ActivityScenario.launch(SettingsActivity.class)){await(s,a->a.root.isShown());s.onActivity(a->{androidx.appcompat.app.AlertDialog d=Ui.dialog(a).setTitle("Private key preview").setMessage("Synthetic only").setPositiveButton("Done",null).show();assertTrue("Modal window can be captured independently of its activity",(d.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);d.dismiss();});}}
 @Test public void untrustedTlsProxyCannotReceiveTheRequest()throws Exception{
  try{Session.request("https://10.0.2.2:5443/audit/capture",new JSONObject().put("syntheticSecret","never-a-real-token"),"synthetic-only",true);fail("Untrusted TLS certificate was accepted");}
  catch(javax.net.ssl.SSLException expected){}
  JSONObject stats=Session.request("http://10.0.2.2:5001/audit/stats",new JSONObject(),null,true);assertEquals(0,stats.getInt("tlsRequests"));
 }
 @Test public void redirectCannotForwardBearerOrBodyToAnotherEndpoint()throws Exception{
  try{Session.request("http://10.0.2.2:5001/audit/redirect",new JSONObject().put("syntheticSecret","never-a-real-token"),"synthetic-only",true);fail("Redirect was accepted");}catch(java.io.IOException expected){if(expected instanceof Session.BackendException)assertEquals(302,((Session.BackendException)expected).status);}
  JSONObject stats=Session.request("http://10.0.2.2:5001/audit/stats",new JSONObject(),null,true);assertEquals(0,stats.getInt("redirectCaptures"));
 }
 @Test public void lateDialogCannotAppearAboveAnAccountGate()throws Exception{
  CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);androidx.appcompat.app.AlertDialog[] dialog={null};
  try(ActivityScenario<SettingsActivity> s=ActivityScenario.launch(SettingsActivity.class)){
   await(s,a->a.root.isShown());s.moveToState(Lifecycle.State.CREATED);Repository.local(context,v->{held.countDown();release.await(15,TimeUnit.SECONDS);return true;},ok->{},error->{});assertTrue(held.await(10,TimeUnit.SECONDS));s.moveToState(Lifecycle.State.RESUMED);
   s.onActivity(a->{dialog[0]=Ui.dialog(a).setTitle("Late callback").setMessage("Synthetic only").show();assertEquals(0f,dialog[0].getWindow().getAttributes().alpha,0f);assertTrue((dialog[0].getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);});
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertFalse(dialog[0].isShowing());release.countDown();await(s,a->a.root.isShown());
  }finally{release.countDown();}
 }
 interface Ready{boolean ok(SettingsActivity a);}static void await(ActivityScenario<SettingsActivity> s,Ready r){for(int i=0;i<100;i++){boolean[] ok={false};s.onActivity(a->ok[0]=r.ok(a));if(ok[0])return;SystemClock.sleep(100);}fail("Private screen did not open");}
 static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=find(((ViewGroup)v).getChildAt(i),text);if(f!=null)return f;}return null;}
}
