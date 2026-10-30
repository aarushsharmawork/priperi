package com.priperi;
import android.app.Activity;import android.content.*;import android.net.Uri;import android.os.SystemClock;import androidx.test.core.app.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import java.io.*;import java.lang.reflect.*;import java.nio.file.Files;import java.util.*;import java.util.concurrent.*;import static org.junit.Assert.*;
/** Only disposable app-cache sentinels. Tests hostile document-picker results, not live files. */
@RunWith(AndroidJUnit4.class) public class DocumentResultTest {
 private final Context context=ApplicationProvider.getApplicationContext();private File source,sentinel;private static final byte[] KEEP="SYNTHETIC_PRIVATE_FILE_MUST_NOT_BE_TRUNCATED".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();Repository.local(context,job,f::complete,e->f.completeExceptionally(new AssertionError(e)));return f.get(30,TimeUnit.SECONDS);}
 private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 @Before public void setup()throws Exception{assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();local(v->{assertNull(v.account());String uid=UUID.randomUUID().toString();JSONObject i=v.identity();v.account(new JSONObject().put("uid",uid).put("username","qa_document").put("hpke",i.getString("hpke")).put("signing",i.getString("signing")).put("fingerprint",i.getString("fingerprint")));v.session(new JSONObject().put("uid",uid).put("access_token","test-only-token").put("refresh_token","test-only-refresh").put("expires_at",System.currentTimeMillis()/1000+3600));Session.restore(v);return true;});source=File.createTempFile("qa-document-source-",".enc",context.getCacheDir());sentinel=File.createTempFile("qa-private-sentinel-",".bin",context.getCacheDir());Files.write(source.toPath(),new byte[64]);Files.write(sentinel.toPath(),KEEP);}
 @After public void cleanup()throws Exception{source.delete();sentinel.delete();local(v->{Repository.eraseLocal(v);return true;});Repository.reset();}
 private Intent hostile(){return new Intent().setData(Uri.fromFile(sentinel));}
 @Test public void onlyProviderDocumentsAreAccepted(){
  assertTrue(DocumentUris.isProviderDocument(Uri.parse("content://com.android.externalstorage.documents/document/primary%3Akit")));
  for(String value:new String[]{"file:///data/user/0/com.priperi/files/vault","https://example.com/kit","content:/missing-authority","content:///missing-authority"})assertFalse(value,DocumentUris.isProviderDocument(Uri.parse(value)));
  assertFalse(DocumentUris.isProviderDocument(null));
 }
 @Test public void recoveryExportCannotOverwriteAnAppPrivatePathFromAFileUri()throws Exception{
  try(ActivityScenario<RecoveryActivity> screen=ActivityScenario.launch(RecoveryActivity.class)){
   for(int i=0;i<100;i++){boolean[] ready={false};screen.onActivity(a->ready[0]=a.dialogAllowed());if(ready[0])break;SystemClock.sleep(30);}screen.onActivity(a->{try{Object model=field(a,"model");set(model,"offer",new AccountRecovery.Offer(new byte[32],source,new JSONObject().put("uid",Session.uid())));a.onActivityResult(141,Activity.RESULT_OK,hostile());}catch(Exception e){throw new AssertionError(e);}});
   waitIdle(screen,"model");assertArrayEquals("Recovery trusted a file:// result and overwrote a private file",KEEP,Files.readAllBytes(sentinel.toPath()));
  }
 }
 @Test public void transferExportCannotOverwriteAnAppPrivatePathFromAFileUri()throws Exception{
  try(ActivityScenario<TransferActivity> screen=ActivityScenario.launch(TransferActivity.class)){
   screen.onActivity(a->{try{set(a,"saveSourceFile",source);a.onActivityResult(119,Activity.RESULT_OK,hostile());}catch(Exception e){throw new AssertionError(e);}});waitIdle(screen,null);assertArrayEquals("Transfer trusted a file:// result and overwrote a private file",KEEP,Files.readAllBytes(sentinel.toPath()));
  }
 }
 @Test public void normalMessageBackupAlsoRejectsFileUris()throws Exception{
  try(ActivityScenario<BackupActivity> screen=ActivityScenario.launch(BackupActivity.class)){
   screen.onActivity(a->{try{Object model=field(a,"model");set(model,"file",source);a.onActivityResult(91,Activity.RESULT_OK,hostile());}catch(Exception e){throw new AssertionError(e);}});assertArrayEquals(KEEP,Files.readAllBytes(sentinel.toPath()));
  }
 }
 private static <T extends Activity> void waitIdle(ActivityScenario<T> screen,String model)throws Exception{for(int i=0;i<120;i++){boolean[] busy={true};screen.onActivity(a->{try{Object target=model==null?a:field(a,model),value=field(target,"busy");busy[0]=value instanceof androidx.lifecycle.MutableLiveData?Boolean.TRUE.equals(((androidx.lifecycle.MutableLiveData<?>)value).getValue()):Boolean.TRUE.equals(value);}catch(Exception e){throw new AssertionError(e);}});if(!busy[0])return;SystemClock.sleep(30);}fail("Document copy never completed");}
}
