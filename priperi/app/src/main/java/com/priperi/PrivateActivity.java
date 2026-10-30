package com.priperi;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

public abstract class PrivateActivity extends GuardedActivity {
    protected android.widget.LinearLayout root;
    private boolean checking, unlocking, resumed;
    private LinearLayout gate;
    private String checkedUid;
    private long checkedGeneration;
    private final android.os.Handler gateHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable delayedGate=()->{if(resumed&&!environmentBlocked&&(checking||unlocking)&&!isFinishing()&&!isDestroyed())displayGate();};
    @Override protected void onResume() {
        super.onResume(); resumed = true; if (environmentBlocked) return;
        checkAccount();
    }
    private void checkAccount(){
        showGate();
        if (unlocking || checking) return;
        if (Session.uid() == null) { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish(); return; }
        checking = true; String user = Session.uid();long generation=Session.generation();
        Repository.work(this, v -> { if (!Session.localSessionMatches(v)||!user.equals(Session.uid())||generation!=Session.generation()) throw new SecurityException("Sign in to continue."); if(v.transferIn()!=null&&v.transferIn().optBoolean("recovery"))return 3;if(v.transferOut()!=null||v.transferIn()!=null)return 2;if(v.policyNeedsCheck()){org.json.JSONObject policy=Repository.call(v,"getAccountPolicy",new org.json.JSONObject());v.accountPolicy(policy);}return v.setting("lock",false)?1:0; }, lock -> {
            checking = false; if (isFinishing() || isDestroyed() || !resumed || environmentBlocked) return;
            if (!user.equals(Session.uid())||generation!=Session.generation()) { recreate(); return; }
            if(lock==3){startActivity(new Intent(this,RecoveryActivity.class));finish();return;}
            if(lock==2){startActivity(new Intent(this,TransferActivity.class));finish();return;}
            checkedUid = user;
            checkedGeneration=generation;
            if (lock==1 && PriperiApp.locked) {
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                Intent intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Priperi", "Use your phone’s screen lock.");
                if (intent == null) { finishAffinity(); return; } unlocking = true; startActivityForResult(intent, 83);
            } else showPrivateContent();
        }, error -> { checking = false; if (resumed && !isFinishing() && !isDestroyed() && !environmentBlocked)showProblem(error); });
    }
    private void showGate() {
        if (root != null) root.setVisibility(View.INVISIBLE);
        gateHandler.removeCallbacks(delayedGate);gateHandler.postDelayed(delayedGate,180);
    }
    private void displayGate(){
        if (gate == null) {
            gate = Ui.loading(this,"Opening Priperi…");
            gate.setTag("private-loading");
            ((ViewGroup)findViewById(android.R.id.content)).addView(gate,new ViewGroup.LayoutParams(-1,-1));
        }
        gate.setVisibility(View.VISIBLE);
    }
    private void showProblem(String message){
        gateHandler.removeCallbacks(delayedGate);displayGate();gate.removeAllViews();
        gate.addView(Ui.title(this,"Couldn’t open your account",22));Ui.space(this,gate,16);gate.addView(Ui.text(this,message,15,R.color.muted));Ui.space(this,gate,20);
        gate.addView(Ui.button(this,"Try again",true,v->{((ViewGroup)gate.getParent()).removeView(gate);gate=null;checkAccount();}));gate.addView(Ui.button(this,"Close",false,v->finishAffinity()));
    }
    private void showPrivateContent() {
        if (!resumed || environmentBlocked || checkedUid == null || !checkedUid.equals(Session.uid())||checkedGeneration!=Session.generation()) return;
        gateHandler.removeCallbacks(delayedGate);
        if (gate != null) gate.setVisibility(View.GONE);
        if (root != null) root.setVisibility(View.VISIBLE);PriperiApp.privateForeground=true;ready();AccountPolicy.resumed(this);
    }
    boolean privateContentAvailable(){return resumed&&!environmentBlocked&&checkedUid!=null&&checkedUid.equals(Session.uid())&&root!=null&&root.isShown();}
    protected abstract void ready();
    void requireSignIn() {
        if (isFinishing() || isDestroyed()) return;
        if (root != null) root.setVisibility(View.INVISIBLE);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish();
    }
    @Override protected void onPause() { resumed = false;PriperiApp.privateForeground=false;gateHandler.removeCallbacks(delayedGate); super.onPause(); }
    @Override protected void onDestroy(){gateHandler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override protected void onStop() { super.onStop(); if (root != null) root.setVisibility(View.INVISIBLE); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 83) { unlocking = false; if (result == RESULT_OK) { PriperiApp.locked = false; showPrivateContent(); } else finishAffinity(); }
    }
}
