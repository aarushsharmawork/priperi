package com.priperi;

import android.os.*;
import android.widget.*;
import android.view.*;
import android.app.Dialog;
import java.util.*;
import androidx.appcompat.app.AppCompatActivity;

abstract class GuardedActivity extends AppCompatActivity {
    protected boolean environmentBlocked;
    private final Set<Dialog> privateDialogs=Collections.newSetFromMap(new IdentityHashMap<>());
    boolean dialogAllowed(){return !environmentBlocked&&!isFinishing()&&!isDestroyed()&&getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)&&(!(this instanceof PrivateActivity)||((PrivateActivity)this).privateContentAvailable());}
    void guardDialog(Dialog dialog){
        privateDialogs.add(dialog);View decor=dialog.getWindow().getDecorView();decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){if(!dialogAllowed())hideDialog(dialog);}public void onViewDetachedFromWindow(View v){privateDialogs.remove(dialog);v.removeOnAttachStateChangeListener(this);}});
        if(!dialogAllowed())hideDialog(dialog);
    }
    private void hideDialog(Dialog dialog){android.view.Window window=dialog.getWindow();WindowManager.LayoutParams attributes=window.getAttributes();attributes.alpha=0;window.setAttributes(attributes);window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);securityHandler.post(()->{clearInputs(window.getDecorView());dialog.dismiss();privateDialogs.remove(dialog);});}
    private static void clearInputs(View view){if(view instanceof EditText)((EditText)view).setText("");if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearInputs(group.getChildAt(i));}}
    private void dismissDialogs(){for(Dialog dialog:new ArrayList<>(privateDialogs)){clearInputs(dialog.getWindow().getDecorView());dialog.dismiss();}privateDialogs.clear();}

    private final Handler securityHandler = new Handler(Looper.getMainLooper());
    private final Runnable monitor = new Runnable() {
        public void run() { if (isFinishing() || isDestroyed()) return; checkEnvironment(); securityHandler.postDelayed(this, 1500); }
    };
    @Override protected void onCreate(Bundle saved) { super.onCreate(saved); DeviceSecurity.secureWindow(this); }
    @Override protected void onResume() {
        super.onResume();dismissDialogs();checkEnvironment(); securityHandler.removeCallbacks(monitor); securityHandler.postDelayed(monitor, 1500);
    }
    @Override protected void onPause() { securityHandler.removeCallbacks(monitor);dismissDialogs();super.onPause(); }
    protected void onEnvironmentBlocked() {}
    private void checkEnvironment() {
        String reason = DeviceSecurity.blockedReason(this);
        if (reason == null) { if (environmentBlocked) recreate(); return; }
        if (environmentBlocked) return; environmentBlocked = true;dismissDialogs();onEnvironmentBlocked();
        LinearLayout page = Ui.page(this), body = Ui.scroll(this, page);
        Ui.space(this, body, 48); body.addView(Ui.symbol(this, "shield", 64, R.color.accent));
        Ui.space(this, body, 24); body.addView(Ui.title(this, "Priperi is locked", 26)); Ui.space(this, body, 12);
        body.addView(Ui.text(this, reason, 16, R.color.muted)); Ui.space(this, body, 24);
        body.addView(Ui.button(this, "Check again", true, v -> { if (DeviceSecurity.blockedReason(this) == null) recreate(); }));
    }
}
