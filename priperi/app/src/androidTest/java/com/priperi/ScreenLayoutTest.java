package com.priperi;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in, synthetic UI fixture. Host selects window size/density/font per process. */
@RunWith(AndroidJUnit4.class)
public class ScreenLayoutTest {
    private Context context;
    private static final String GROUP="3ec3140e-9163-4267-8c22-3f74bd08532e";
    private static final String ALICE="00000000-0000-4000-8000-000000000001",BOB="00000000-0000-4000-8000-000000000002";
    @Test public void screensFitAndDraftSurvivesRotationWithSafeSend() throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("screenMatrix")));
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        context=ApplicationProvider.getApplicationContext();String label=InstrumentationRegistry.getArguments().getString("screenLabel","phone");
        int theme=Integer.parseInt(InstrumentationRegistry.getArguments().getString("theme","0"));
        context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).putBoolean("enable_after_sign_in",false).commit();
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putInt("theme",theme).commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->PriperiApp.applyTheme(theme));
        Repository.reset();local(v->{if(v.account()!=null&&!ALICE.equals(v.account().optString("uid")))throw new SecurityException("Disposable installation required");Repository.eraseLocal(v);return true;});
        try {
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                await(main,a->{View button=findText(a.getWindow().getDecorView(),"Continue");return button!=null&&button.getWidth()>0;});
                main.onActivity(a->{View button=findText(a.getWindow().getDecorView(),"Continue");button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);});
                settle();main.onActivity(a->{visibleInside(a,findText(a.getWindow().getDecorView(),"Continue"));save(a,label+"-setup");});
                for(String method:new String[]{"pendingMove","missingKey","replacementForm"}){
                    main.onActivity(a->{try{java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod(method);show.setAccessible(true);show.invoke(a);}catch(Exception e){throw new AssertionError(e);}});settle();
                    main.onActivity(a->{checkHorizontal(a,a.getWindow().getDecorView());String button=method.equals("pendingMove")?"Resume account move":method.equals("replacementForm")?"Create new account":"Recovery options";View control=findText(a.getWindow().getDecorView(),button);control.requestRectangleOnScreen(new Rect(0,0,control.getWidth(),control.getHeight()),true);});settle();
                    main.onActivity(a->{visibleInside(a,findText(a.getWindow().getDecorView(),method.equals("pendingMove")?"Resume account move":method.equals("replacementForm")?"Create new account":"Recovery options"));save(a,label+"-"+method);});
                }
                main.onActivity(a->{LinearLayout page=Ui.page(a);page.addView(Ui.loading(a,"Opening Priperi…"),new LinearLayout.LayoutParams(-1,-1));});settle();main.onActivity(a->{checkHorizontal(a,a.getWindow().getDecorView());visibleInside(a,findText(a.getWindow().getDecorView(),"Priperi"));visibleInside(a,findText(a.getWindow().getDecorView(),"Opening Priperi…"));save(a,label+"-loading");});
            }
            local(v->{try(Vault bob=new Vault(context,"screen_layout_peer")) {
                JSONObject me=profile(ALICE,"abcdefghijklmnopqrstuvwx",v),peer=profile(BOB,"a_very_long_username_123",bob);v.account(me);peer.put("contactState",new JSONObject().put("uid",BOB).put("sentCount",0).put("approved",false).put("incomingPending",true));TestIdentityTrust.pin(v,peer);v.setSetting("notifications",false);
                for(int i=0;i<12;i++){
                    String text=i==11?"A longer message should wrap comfortably on a compact screen, without running under the edge. "+"abcdefghij".repeat(12):"Message "+i;
                    JSONObject envelope=bob.crypto().encrypt(BOB,bob.identity(),me,text);
                    v.store(new JSONObject().put("peer",BOB).put("body",text).put("envelope",envelope).put("outgoing",false).put("state","received"));
                }
                JSONObject edited=v.crypto().encrypt(ALICE,v.identity(),peer,"A message with a pending edit that should wrap neatly.");v.store(new JSONObject().put("peer",BOB).put("body","An edited message whose change is waiting to send.").put("envelope",edited).put("outgoing",true).put("state","delivered").put("edited",true).put("changeState","pending"));
                JSONObject removed=bob.crypto().encrypt(BOB,bob.identity(),me,"Removed content");v.store(new JSONObject().put("peer",BOB).put("body","").put("envelope",removed).put("outgoing",false).put("state","received").put("deleted",true));JSONObject answer=bob.crypto().encrypt(BOB,bob.identity(),me,"A reply to a message that was removed.");v.store(new JSONObject().put("peer",BOB).put("body","A reply to a message that was removed.").put("envelope",answer).put("outgoing",false).put("state","received").put("replyTo",removed.getString("id")));
                org.json.JSONArray members=new org.json.JSONArray().put(new JSONObject(me.toString()).put("admin",true)).put(new JSONObject(peer.toString()).put("admin",false));
                TestIdentityTrust.groups(v,new JSONObject().put("groups",new org.json.JSONArray().put(new JSONObject().put("id",GROUP).put("name","A group name that should fit comfortably on a small screen").put("version",1).put("members",members))).put("invites",new org.json.JSONArray().put(new JSONObject().put("id","65c5bd1a-93c5-4d4d-9de0-701621e16a25").put("name","A pending group invitation").put("inviter","a_friend"))));
                JSONObject ge=bob.crypto().encryptGroup(BOB,bob.identity(),me,"A private group message that wraps on compact screens.",java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),GROUP,1);v.store(new JSONObject().put("peer","group:"+GROUP).put("body","A private group message that wraps on compact screens.").put("outgoing",false).put("state","received").put("envelope",ge).put("author",peer));
                v.session(new JSONObject().put("uid",ALICE).put("access_token","test-only-token").put("refresh_token","test-refresh").put("expires_at",System.currentTimeMillis()/1000+3600));Session.restore(v);return true;
            }});
            try(ActivityScenario<InboxActivity> inbox=ActivityScenario.launch(InboxActivity.class)) {
                await(inbox,a->{View view=findDescription(a.getWindow().getDecorView(),"Settings");return view!=null&&view.isShown();});
                settle();inbox.onActivity(a->{assertNull(findDescription(a.getWindow().getDecorView(),"Refresh chats"));visibleInside(a,findDescription(a.getWindow().getDecorView(),"Settings"));visibleInside(a,findText(a.getWindow().getDecorView(),"New chat"));save(a,label+"-inbox");tap(findText(a.root,"Groups · 1"));});
                settle();inbox.onActivity(a->{assertNotNull(findText(a.root,"Accept"));assertNotNull(findText(a.root,"Decline"));checkHorizontal(a,a.root);save(a,label+"-groups");});
            }
            Intent chatIntent=new Intent(context,ChatActivity.class).putExtra("peer",BOB).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try(ActivityScenario<ChatActivity> chat=ActivityScenario.launch(chatIntent)) {
                await(chat,a->a.root.isShown()&&findDescription(a.getWindow().getDecorView(),"Send message")!=null&&findText(a.root,"Message 0")!=null);
                local(v->{TestIdentityTrust.unverify(v,BOB);return true;});chat.recreate();await(chat,a->a.root.isShown()&&findDescription(a.root,"Chat details")!=null);settle();chat.onActivity(a->{findComposer(a.root).setText("Username-only message");assertTrue(findDescription(a.root,"Send message").isEnabled());checkHorizontal(a,a.root);save(a,label+"-optional-verification");findComposer(a.root).setText("");});local(v->{v.contactFlag(BOB,"verified",true);return true;});chat.recreate();await(chat,a->a.root.isShown()&&findText(a.root,"Message 0")!=null);
                chat.onActivity(a->{findText(a.root,"Message 0").performLongClick();try{java.lang.reflect.Field f=ChatActivity.class.getDeclaredField("actionDialog");f.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)f.get(a);dialog.getListView().performItemClick(null,0,0);}catch(Exception e){throw new AssertionError(e);}});
                chat.onActivity(a->{EditText input=findComposer(a.getWindow().getDecorView());input.setText("A draft stays through rotation.");});
                chat.recreate();await(chat,a->a.root.isShown()&&"A draft stays through rotation.".contentEquals(findComposer(a.getWindow().getDecorView()).getText())&&findDescription(a.root,"Send message").isEnabled()&&findDescription(a.root,"Reply · Message 0")!=null);
                settle();chat.onActivity(a->{View button=findDescription(a.getWindow().getDecorView(),"Send message");assertTrue(button.isEnabled());visibleInside(a,button);assertTrue(button.getWidth()>=Ui.dp(a,48));checkHorizontal(a,a.root);save(a,label+"-chat");EditText input=findComposer(a.root);input.requestFocus();a.getSystemService(InputMethodManager.class).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});
                await(chat,a->{WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(a.root);return insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime());});
                settle();chat.onActivity(a->{visibleInside(a,findComposer(a.root));visibleInside(a,findDescription(a.root,"Send message"));assertTrue("Message history collapsed under the keyboard",findHistory(a.root).getHeight()>=Ui.dp(a,24));save(a,label+"-keyboard");View button=findDescription(a.root,"Send message");button.performClick();findComposer(a.root).setText("The next draft must not be erased.");});
                local(v->true);await(chat,a->"The next draft must not be erased.".contentEquals(findComposer(a.root).getText())&&findDescription(a.root,"Send message").isEnabled());
                local(v->{int count=0;for(JSONObject message:v.messages(BOB))if(message.optBoolean("outgoing")&&"A draft stays through rotation.".equals(message.optString("body")))count++;if(count!=1)throw new SecurityException("Send was duplicated or lost");return true;});
            }
            try(ActivityScenario<SettingsActivity> settings=ActivityScenario.launch(SettingsActivity.class)) {
                await(settings,a->a.root.isShown()&&findText(a.root,"Appearance")!=null);
                settings.onActivity(a->assertNull(findText(a.root,"Optimise storage")));
                settle();settings.onActivity(a->{checkHorizontal(a,a.root);save(a,label+"-settings");View delete=findText(a.root,"Delete account");delete.requestRectangleOnScreen(new Rect(0,0,delete.getWidth(),delete.getHeight()),true);});
                settle();settings.onActivity(a->visibleInside(a,findText(a.root,"Delete account")));
            }
            try(ActivityScenario<GroupDetailsActivity> details=ActivityScenario.launch(new Intent(context,GroupDetailsActivity.class).putExtra("group",GROUP))){await(details,a->a.root.isShown()&&findText(a.root,"Add members")!=null);settle();details.onActivity(a->{checkHorizontal(a,a.root);save(a,label+"-group-details");MemberPicker picker=new MemberPicker(a,pickerGroup(),peer->{});picker.show();a.root.setTag(picker);});settle();details.onActivity(a->saveDialog(a,label+"-member-picker"));InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);settle();details.onActivity(a->{View leave=findText(a.root,"Leave group");leave.requestRectangleOnScreen(new Rect(0,0,leave.getWidth(),leave.getHeight()),true);});settle();details.onActivity(a->visibleInside(a,findText(a.root,"Leave group")));}
            try(ActivityScenario<ChatActivity> chat=ActivityScenario.launch(new Intent(context,ChatActivity.class).putExtra("peer","group:"+GROUP))){await(chat,a->a.root.isShown()&&findComposer(a.root)!=null);settle();chat.onActivity(a->{findComposer(a.root).setText("A group draft survives rotation.");});settle();chat.onActivity(a->{checkHorizontal(a,a.root);visibleInside(a,findDescription(a.root,"Send message"));save(a,label+"-group-chat");findText(a.root,"A private group message that wraps on compact screens.").performLongClick();});settle();chat.onActivity(a->saveDialog(a,label+"-message-actions"));InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);settle();chat.recreate();await(chat,a->a.root.isShown()&&"A group draft survives rotation.".contentEquals(findComposer(a.root).getText()));}
            try(ActivityScenario<ContactCodeActivity> contact=ActivityScenario.launch(ContactCodeActivity.class)) {
                await(contact,a->a.root.isShown()&&((ImageView)findDescription(a.root,"Your Priperi contact QR code")).getDrawable()!=null);settle();
                contact.onActivity(a->{checkHorizontal(a,a.root);assertNull(findText(a.root,"Read a contact"));assertNull(findText(a.root,"Tap to share with NFC"));save(a,label+"-contact");View symbol=findDescription(a.root,"Connect by NFC");symbol.requestRectangleOnScreen(new Rect(0,0,symbol.getWidth(),symbol.getHeight()),true);});settle();contact.onActivity(a->visibleInside(a,findDescription(a.root,"Connect by NFC")));
                contact.onActivity(a->{View scan=findText(a.root,"Scan a code");scan.requestRectangleOnScreen(new Rect(0,0,scan.getWidth(),scan.getHeight()),true);});settle();contact.onActivity(a->{visibleInside(a,findText(a.root,"Scan a code"));save(a,label+"-contact-qr");});
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pm grant com.priperi.debug android.permission.CAMERA").close();
                contact.onActivity(a->findText(a.root,"Scan a code").performClick());settle();contact.onActivity(a->saveDialog(a,label+"-scanner"));InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);settle();
            }
            try(ActivityScenario<TransferActivity> transfer=ActivityScenario.launch(TransferActivity.class)) {
                await(transfer,a->findText(a.getWindow().getDecorView(),"Move this account to another phone")!=null);settle();transfer.onActivity(a->{checkHorizontal(a,a.getWindow().getDecorView());View move=findText(a.getWindow().getDecorView(),"Move this account to another phone");move.requestRectangleOnScreen(new Rect(0,0,move.getWidth(),move.getHeight()),true);});settle();transfer.onActivity(a->{visibleInside(a,findText(a.getWindow().getDecorView(),"Move this account to another phone"));save(a,label+"-transfer");});
            }
            try(ActivityScenario<RecoveryActivity> recovery=ActivityScenario.launch(RecoveryActivity.class)) {
                await(recovery,a->findText(a.getWindow().getDecorView(),"Create recovery backup")!=null);settle();
                recovery.onActivity(a->checkHorizontal(a,a.getWindow().getDecorView()));
                bringIntoView(recovery,"Create recovery backup");
                recovery.onActivity(a->{visibleInside(a,findText(a.getWindow().getDecorView(),"Create recovery backup"));save(a,label+"-recovery");});
            }
            try(ActivityScenario<BackupActivity> backup=ActivityScenario.launch(BackupActivity.class)) {
                await(backup,a->a.root.isShown()&&findText(a.root,"Create encrypted backup")!=null);settle();backup.onActivity(a->{checkHorizontal(a,a.root);View restore=findText(a.root,"Restore saved messages");restore.requestRectangleOnScreen(new Rect(0,0,restore.getWidth(),restore.getHeight()),true);});settle();backup.onActivity(a->{visibleInside(a,findText(a.root,"Restore saved messages"));save(a,label+"-backup");});
            }
        } finally {local(v->{Repository.eraseLocal(v);return true;});Repository.reset();context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit();}
    }
    private static JSONObject pickerGroup(){try{return new JSONObject().put("members",new org.json.JSONArray().put(new JSONObject().put("uid",ALICE)));}catch(Exception e){throw new AssertionError(e);}}
    private static void tap(View v){assertNotNull(v);while(!v.isClickable()&&v.getParent() instanceof View)v=(View)v.getParent();assertTrue(v.performClick());}
    private static void saveDialog(Activity a,String name){try{java.lang.reflect.Field field=a instanceof ChatActivity?ChatActivity.class.getDeclaredField("actionDialog"):a instanceof ContactCodeActivity?ContactCodeActivity.class.getDeclaredField("cameraDialog"):null;if(field!=null){field.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field.get(a);assertNotNull(dialog);saveView(a,dialog.getWindow().getDecorView(),name);}else{java.lang.reflect.Field memberDialog=MemberPicker.class.getDeclaredField("dialog");memberDialog.setAccessible(true);androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)memberDialog.get(((PrivateActivity)a).root.getTag());assertNotNull(dialog);View member=findText(dialog.getWindow().getDecorView(),"@a_very_long_username_123");assertNotNull(member);Rect visible=new Rect();assertTrue("Member row is clipped",member.getGlobalVisibleRect(visible)&&visible.height()>=member.getHeight()-2);saveView(a,dialog.getWindow().getDecorView(),name);}}catch(Exception e){throw new AssertionError(e);}}
    private interface Ready<T extends Activity>{boolean check(T activity);}
    private static <T extends Activity> void await(ActivityScenario<T> scenario,Ready<T> ready){for(int i=0;i<120;i++){boolean[] done={false};scenario.onActivity(a->done[0]=ready.check(a));if(done[0])return;SystemClock.sleep(100);}fail("Layout did not become usable");}
    private void local(Repository.Job<Boolean> job)throws Exception {CountDownLatch done=new CountDownLatch(1);String[] error={null};Repository.local(context,job,ok->done.countDown(),message->{error[0]=message;done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));assertNull(error[0]);}
    private static JSONObject profile(String uid,String name,Vault v)throws Exception {JSONObject i=v.identity();return new JSONObject().put("uid",uid).put("username",name).put("hpke",i.getString("hpke")).put("signing",i.getString("signing")).put("fingerprint",i.getString("fingerprint"));}
    private static EditText findComposer(View v){if(v instanceof EditText&&"Message".contentEquals(((EditText)v).getHint()))return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText found=findComposer(g.getChildAt(i));if(found!=null)return found;}}return null;}
    private static ScrollView findHistory(View v){if(v instanceof ScrollView)return (ScrollView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){ScrollView found=findHistory(g.getChildAt(i));if(found!=null)return found;}}return null;}
    private static View findText(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findText(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static View findDescription(View v,String text){if(v.getContentDescription()!=null&&text.contentEquals(v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findDescription(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void visibleInside(Activity a,View v){assertNotNull(v);Rect visible=new Rect();assertTrue("Control is off screen",v.getGlobalVisibleRect(visible));assertTrue("Control is clipped vertically: "+visible+" height="+v.getHeight(),visible.height()>=v.getHeight()-2);View root=((android.view.ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);int[] pos=new int[2];root.getLocationOnScreen(pos);assertTrue("Control is under the keyboard/navigation area",visible.bottom<=pos[1]+root.getHeight()-root.getPaddingBottom()+2);assertTrue(visible.left>=pos[0]+root.getPaddingLeft()-2);assertTrue(visible.right<=pos[0]+root.getWidth()-root.getPaddingRight()+2);}
    private static void checkHorizontal(Activity a,View view){if(view.getVisibility()!=View.VISIBLE)return;if(view instanceof HorizontalScrollView)return;if(view instanceof TextView){int[] pos=new int[2];view.getLocationOnScreen(pos);View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);int[] r=new int[2];root.getLocationOnScreen(r);assertTrue("Text overflows left",pos[0]>=r[0]-2);assertTrue("Text overflows right",pos[0]+view.getWidth()<=r[0]+root.getWidth()+2);}if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)checkHorizontal(a,g.getChildAt(i));}}
    // Status arrives asynchronously and can reflow at large font sizes. Scroll after layout,
    // retaining the same full-control assertion and a bounded failure if it cannot fit.
    private static <T extends Activity> void bringIntoView(ActivityScenario<T> screen,String text){
        int stable=0;
        for(int attempt=0;attempt<12;attempt++){
            screen.onActivity(a->{View control=findText(a.getWindow().getDecorView(),text);if(control!=null)control.requestRectangleOnScreen(new Rect(0,0,control.getWidth(),control.getHeight()),true);});settle();
            boolean[] full={false};screen.onActivity(a->{View control=findText(a.getWindow().getDecorView(),text);if(control==null)return;Rect rect=new Rect();full[0]=control.getHeight()>0&&control.getGlobalVisibleRect(rect)&&rect.height()>=control.getHeight()-2;});
            stable=full[0]?stable+1:0;if(stable>=2)return;
        }
        fail("Control cannot be scrolled fully into view: "+text);
    }
    private static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(350);}
    private static void save(Activity a,String name){saveView(a,a.getWindow().getDecorView(),name);}
    private static void saveView(Activity a,View root,String name){try{Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));File dir=new File(a.getExternalFilesDir(null),"layout-previews");dir.mkdirs();try(FileOutputStream file=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,file);}image.recycle();}catch(IOException e){throw new AssertionError(e);}}
}
