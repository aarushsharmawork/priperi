package com.priperi;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;

public final class MainActivity extends GuardedActivity {
    private LinearLayout root;
    private TextView status;
    private EditText username,password,previousPassword;
    private MaterialButton submit;
    private boolean busy,rendered,returning,waitingForNotificationPermission;
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);root=Ui.page(this);}
    @Override protected void onResume(){
        super.onResume();if(environmentBlocked||waitingForNotificationPermission)return;
        if(!rendered&&!busy&&Notices.requestAtStart(this)){
            waitingForNotificationPermission=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,60);body.addView(Ui.title(this,"Welcome to Priperi",28));Ui.space(this,body,16);body.addView(Ui.text(this,"Choose whether you’d like new message alerts. You can change this later in Settings.",16,R.color.muted));return;
        }
        if(!rendered&&!busy)route();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==Notices.STARTUP_PERMISSION){waitingForNotificationPermission=false;Notices.startupPermissionResult(this,results);if(!environmentBlocked&&!rendered&&!busy)route();}
    }
    private boolean alive(){return !isFinishing()&&!isDestroyed()&&!environmentBlocked;}
    private void route(){
        busy=true;root.removeAllViews();root.addView(Ui.loading(this,"Checking this phone…"),new LinearLayout.LayoutParams(-1,-1));
        Repository.local(this,v->{
            if(v.transferIn()!=null&&v.transferIn().optBoolean("recovery"))return new JSONObject().put("recoveryPending",true);if(v.transferOut()!=null||v.transferIn()!=null)return new JSONObject().put("movePending",true);
            if(v.resetRequest()!=null)return new JSONObject().put("resetPending",true);
            if(Session.localSessionMatches(v))return new JSONObject().put("open",true);if(Session.uid()!=null)Session.forget();
            try{JSONObject state=Session.deviceStatus(v);if(v.account()!=null&&!state.optBoolean("registered",true)&&v.policyNeedsCheck()){SettingsActivity.removeLocalAccount(this,v);return new JSONObject().put("newStart",true);}return state.put("localAccount",v.account()!=null);}
            catch(Session.BackendException error){if("device-key-missing".equals(error.reason))return new JSONObject().put("keyMissing",true);if("account-expired".equals(error.reason)){SettingsActivity.removeLocalAccount(this,v);return new JSONObject().put("newStart",true);}if(v.account()!=null)return new JSONObject().put("registered",true).put("offline",true);throw error;}
            catch(java.io.IOException error){if(v.account()!=null)return new JSONObject().put("registered",true).put("offline",true);throw error;}
        },state->{busy=false;if(!alive())return;if(state.optBoolean("keyMissing")){missingKey();return;}if(state.optBoolean("newStart")){rendered=false;route();return;}if(state.optBoolean("recoveryPending")){recoveryPending();return;}if(state.optBoolean("movePending")){pendingMove();return;}if(state.optBoolean("open")){openInbox();return;}if(state.optBoolean("resetPending")){resetPending();return;}
            if(state.optBoolean("localAccount")&&!state.optBoolean("registered")){missingAccount();return;}
            if(state.optBoolean("replacement")){replacementForm();username.setText(state.optString("pendingUsername"));username.setEnabled(false);return;}
            form(state.optBoolean("registered"),state.optString("pendingUsername"));if(state.optBoolean("offline"))status.setText("You’re offline. Connect to the internet to sign in.");
        },message->{busy=false;if(!alive())return;root.removeAllViews();LinearLayout content=Ui.scroll(this,root);Ui.space(this,content,60);content.addView(Ui.title(this,"Couldn’t check this phone",26));Ui.space(this,content,12);content.addView(Ui.text(this,message,15,R.color.muted));Ui.space(this,content,20);content.addView(Ui.button(this,"Try again",true,v->route()));});
    }
    private void form(boolean registered,String pendingName){
        rendered=true;returning=registered;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,30);
        LinearLayout brand=Ui.row(this);ImageView icon=Ui.symbol(this,"brand",38,R.color.accent);brand.addView(icon);TextView word=Ui.title(this,"Priperi",27);LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-2,-2);nameParams.setMarginStart(Ui.dp(this,12));brand.addView(word,nameParams);body.addView(brand);
        Ui.space(this,body,36);body.addView(Ui.title(this,registered?"Welcome back":"Let’s set up your account",28));Ui.space(this,body,10);
        body.addView(Ui.text(this,registered?"This phone is linked to your account. Enter your password to open your chats.":"Choose a username so people can find you, then set a password for this phone.",16,R.color.muted));Ui.space(this,body,28);
        LinearLayout card=Ui.card(this);body.addView(card,new LinearLayout.LayoutParams(-1,-2));
        if(!registered){username=Ui.input(this,card,"Username",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);username.setText(pendingName);username.setEnabled(pendingName.isEmpty());card.addView(Ui.text(this,"Unique to you. You can change it later.",12,R.color.muted));Ui.space(this,card,14);}else username=null;
        password=Ui.input(this,card,"Password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(!registered){card.addView(Ui.text(this,"12–72 characters. Include uppercase, lowercase, a number and a symbol.",12,R.color.muted));Ui.space(this,card,14);}
        status=Ui.status(this,card);submit=Ui.button(this,"Continue",true,v->authenticate());card.addView(submit,new LinearLayout.LayoutParams(-1,-2));
        password.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){authenticate();return true;}return false;});password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        if(!registered)body.addView(Ui.button(this,"Restore account from recovery backup",false,v->startActivity(new Intent(this,RecoveryActivity.class))));
        if(!registered)body.addView(Ui.button(this,"Move an account to this phone",false,v->startActivity(new Intent(this,TransferActivity.class))));
        if(registered)body.addView(Ui.button(this,"Forgot password or username?",false,v->confirmReset()));
        Ui.space(this,body,24);LinearLayout note=Ui.row(this);note.addView(Ui.symbol(this,"shield",20,R.color.accent));TextView privacy=Ui.text(this,"One account on this phone. No email or phone number.",12,R.color.muted);LinearLayout.LayoutParams noteParams=new LinearLayout.LayoutParams(0,-2,1);noteParams.setMarginStart(Ui.dp(this,10));note.addView(privacy,noteParams);body.addView(note);
        Ui.space(this,body,12);body.addView(Ui.text(this,"Create a recovery backup in Settings before uninstalling or clearing app data. Keep its recovery words separately.",12,R.color.muted));Ui.fadeIn(this,body);
    }
    private void recoveryPending(){rendered=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,40);body.addView(Ui.title(this,"Account recovery paused",25));body.addView(Ui.text(this,"The encrypted recovery is saved on this installation. Resume to finish safely.",15,R.color.muted));body.addView(Ui.button(this,"Resume account recovery",true,v->startActivity(new Intent(this,RecoveryActivity.class))));}
    private void pendingMove(){
        rendered=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,48);body.addView(Ui.title(this,"Account move paused",27));Ui.space(this,body,14);
        body.addView(Ui.text(this,"Your unfinished move is saved on this phone. Resume it to finish, cancel it or safely return the account. Chats stay protected while the move is pending.",16,R.color.muted));Ui.space(this,body,24);
        body.addView(Ui.button(this,"Resume account move",true,v->startActivity(new Intent(this,TransferActivity.class))));
    }
    private void missingKey(){
        rendered=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,40);body.addView(Ui.title(this,"This account’s key is missing",27));Ui.space(this,body,16);
        body.addView(Ui.text(this,"This installation does not have the private key linked to the account on this phone. Uninstalling or clearing app data removes that key. Your password cannot recreate it, and reinstalling again will not fix it.",16,R.color.muted));Ui.space(this,body,22);
        body.addView(Ui.button(this,"Restore account from recovery backup",true,v->startActivity(new Intent(this,RecoveryActivity.class))));
        body.addView(Ui.button(this,"Create new account",false,v->replacementWarning()));
        body.addView(Ui.button(this,"Recovery options",false,v->Ui.dialog(this).setTitle("What you can do").setMessage("If you saved a recovery kit before uninstalling, choose Restore account and use its file, 24 words and your current app password.\n\nIf another registered phone still holds the account, use its Move account option. An unfinished move may first need to be returned to that phone.\n\nTo start again, choose Create new account and confirm your previous account password. It creates a separate identity, never restores old chats, and schedules the previous account for deletion after 7 days of inactivity. Its username stays reserved until deletion.\n\nIf you also forgot that password or the account cannot be checked, ask the service owner for a reviewed reset. No phone identifier alone can authorise deletion.").setPositiveButton("Done",null).show()));
        body.addView(Ui.button(this,"Check again",false,v->{rendered=false;route();}));
    }
    private void replacementWarning(){
        if(busy)return;
        Ui.dialog(this).setTitle("Start with a new account?").setMessage("Your previous chats and keys will not be available in the new account. It will have a new identity and needs a different available username.\n\nConfirm the previous account password to protect it from someone else requesting deletion. Only after the new account is created will the previous account be signed out and set to delete after 7 days of inactivity. Its username stays reserved until then.\n\nChoose Cancel if you want to try a transfer instead.").setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->replacementForm()).show();
    }
    private void replacementForm(){
        rendered=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,24);body.addView(Ui.title(this,"Create a new account",27));Ui.space(this,body,12);
        body.addView(Ui.text(this,"Old chats cannot be recovered. After signup, the previous account will be deleted after 7 days of inactivity.",15,R.color.muted));Ui.space(this,body,18);
        previousPassword=Ui.input(this,body,"Old password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        username=Ui.input(this,body,"New username",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password=Ui.input(this,body,"New password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(Ui.text(this,"12–72 characters. Include uppercase, lowercase, a number and a symbol.",12,R.color.muted));status=Ui.status(this,body);
        submit=Ui.button(this,"Create new account",true,v->confirmReplacement());body.addView(submit);
        body.addView(Ui.button(this,"Cancel",false,v->{if(!busy){previousPassword.setText("");password.setText("");missingKey();}}));
    }
    private void confirmReplacement(){
        if(busy)return;String name=Ui.value(username),previous=previousPassword.getText().toString(),pass=password.getText().toString();
        if(!Validation.username(name)){username.setError("Choose 3–24 lowercase letters, numbers or _. Start with a letter.");return;}
        if(!Validation.password(previous)){previousPassword.setError("Enter your previous account password.");return;}
        if(!Validation.password(pass)){password.setError("Use 12–72 characters with uppercase, lowercase, a number and a symbol.");return;}
        Ui.dialog(this).setTitle("Create a separate account?").setMessage("Previous chats and keys will not return. The previous username will remain reserved until its account is deleted.\n\nAfter this new account is successfully created, the previous account will be signed out and deleted after 7 days of inactivity, even if its old setting was Never. Cleanup runs hourly while the service is available; outages can delay it. Contacts’ copies, exported files and provider logs or backups are outside app deletion.").setNegativeButton("Cancel",null).setPositiveButton("Create new account",(d,w)->{
            if(busy||!alive())return;performAuthentication(name,pass,previous,true,null);
        }).show();
    }
    private void authenticate(){
        if(busy)return;String name=username==null?null:Ui.value(username),pass=password.getText().toString();
        if(!returning&&!Validation.username(name)){username.setError("3–24 lowercase letters, numbers or _. Start with a letter.");return;}
        if(!Validation.password(pass)){password.setError("Use 12–72 characters with uppercase, lowercase, a number and a symbol.");return;}
        performAuthentication(name,pass,null,false,null);
    }
    private void performAuthentication(String name,String pass,String previous,boolean replacement,String token){
        if(busy||!alive())return;busy=true;submit.setEnabled(false);password.setText("");if(previousPassword!=null)previousPassword.setText("");status.setText(replacement?"Creating your new account…":returning?"Signing in…":"Creating your account…");
        Repository.local(this,v->{try{if(replacement)Session.replaceAccount(v,name,previous,pass,token);else Session.continueAccount(v,name,pass,token);return (String)null;}catch(Session.BackendException error){if(name!=null&&"signup-verification-required".equals(error.reason))return Session.signupBinding(v,name);throw error;}},binding->{
            busy=false;if(!alive())return;if(binding==null){openInbox();return;}
            char[] held=pass.toCharArray(),old=previous==null?null:previous.toCharArray();
            SignupCheck.show(this,binding,verified->{String next=new String(held),prior=old==null?null:new String(old);java.util.Arrays.fill(held,'\0');if(old!=null)java.util.Arrays.fill(old,'\0');if(alive())performAuthentication(name,next,prior,replacement,verified);},error->{java.util.Arrays.fill(held,'\0');if(old!=null)java.util.Arrays.fill(old,'\0');if(alive()){submit.setEnabled(true);status.setText(error);}});
        },message->{busy=false;if(!alive())return;submit.setEnabled(true);status.setText(message);});
    }
    private void missingAccount(){rendered=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,50);body.addView(Ui.title(this,"This account was removed",26));Ui.space(this,body,12);body.addView(Ui.text(this,"Its username and server records are no longer available. Reset this phone’s saved app data to create a new account.",16,R.color.muted));body.addView(Ui.button(this,"Reset this phone",true,v->confirmReset()));status=Ui.status(this,body);}
    private void resetPending(){rendered=true;root.removeAllViews();LinearLayout body=Ui.scroll(this,root);Ui.space(this,body,50);body.addView(Ui.title(this,"Finish account reset",26));Ui.space(this,body,12);body.addView(Ui.text(this,"The last response was interrupted. We’ll check the server before removing anything from this phone.",16,R.color.muted));body.addView(Ui.button(this,"Retry reset",true,v->confirmReset()));status=Ui.status(this,body);}
    private void confirmReset(){
        if(busy)return;
        Ui.dialog(this).setTitle("Reset your account?").setMessage("Your username, account, queued messages and all chats saved in this app on this phone will be deleted. You’ll choose a new username and password. This cannot be undone.\n\nThis uses this phone’s existing private key. It cannot recover an account after that key is lost. Contacts’ copies and provider-retained logs or backups cannot be erased by the app.")
            .setNegativeButton("Cancel",null).setPositiveButton("Reset account",(d,w)->{
                KeyguardManager keyguard=getSystemService(KeyguardManager.class);
                if(keyguard.isDeviceSecure()){Intent check=keyguard.createConfirmDeviceCredentialIntent("Reset Priperi","Confirm with this phone’s screen lock.");if(check!=null){startActivityForResult(check,102);return;}}
                resetNow();
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==102&&result==RESULT_OK)resetNow();}
    private void resetNow(){
        if(busy)return;busy=true;if(submit!=null)submit.setEnabled(false);if(status!=null)status.setText("Resetting your account…");
        Repository.local(this,v->{Session.resetAccount(v);SettingsActivity.removeLocalAccount(this,v);return true;},ok->{busy=false;if(!alive())return;Notices.clear(this);rendered=false;route();},message->{busy=false;if(!alive())return;if(submit!=null)submit.setEnabled(true);if(status!=null)status.setText(message);});
    }
    private void openInbox(){startActivity(new Intent(this,InboxActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();}
}
