package com.priperi;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import androidx.core.graphics.ColorUtils;
import androidx.appcompat.widget.AppCompatImageButton;

/** A compact visual inside a full-size accessible touch target. */
final class SendButton extends AppCompatImageButton {
    private final float travel;
    private float flight;
    private ValueAnimator flightAnimator;
    SendButton(Activity activity) {
        super(activity);
        int accent=activity.getColor(R.color.accent),foreground=activity.getColor(R.color.on_accent);
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{accent,ColorUtils.blendARGB(accent,activity.getColor(R.color.ink),.12f)});
        fill.setCornerRadius(Ui.dp(activity,14));fill.setStroke(Ui.dp(activity,1),ColorUtils.setAlphaComponent(foreground,35));
        GradientDrawable mask=Ui.shape(activity,Color.WHITE,14);
        setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground,65)),
            new InsetDrawable(fill,Ui.dp(activity,4)),new InsetDrawable(mask,Ui.dp(activity,4))));
        androidx.core.view.ViewCompat.setBackgroundTintList(this,null);
        androidx.core.widget.ImageViewCompat.setImageTintList(this,null);
        setImageDrawable(new Icons("send",foreground));setScaleType(ScaleType.FIT_CENTER);Ui.pad(activity,this,14);
        setContentDescription("Send message");setFocusable(true);setFilterTouchesWhenObscured(true);
        setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)));
        setEnabled(false);setAlpha(.48f);travel=Ui.dp(activity,8);
    }
    void ready(boolean enabled) {
        boolean changed=enabled!=isEnabled();setEnabled(enabled);setAlpha(enabled?1f:.48f);
        if(changed&&enabled&&isLaidOut()&&ValueAnimator.areAnimatorsEnabled()) {
            setScaleX(.94f);setScaleY(.94f);animate().scaleX(1).scaleY(1).setDuration(160).setInterpolator(new OvershootInterpolator(1.1f)).start();
        }
    }
    @Override public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if(isLaidOut()&&ValueAnimator.areAnimatorsEnabled())animate().scaleX(pressed?.92f:1).scaleY(pressed?.92f:1).setDuration(pressed?80:160).setInterpolator(new OvershootInterpolator(1.1f)).start();
    }
    void committed() {
        if(!ValueAnimator.areAnimatorsEnabled())return;
        if(flightAnimator!=null)flightAnimator.cancel();
        flightAnimator=ValueAnimator.ofFloat(0,1);flightAnimator.setDuration(260);
        flightAnimator.addUpdateListener(a->{flight=(float)a.getAnimatedValue();setImageAlpha(Math.round(255*Math.abs(1-2*flight)));invalidate();});
        flightAnimator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){flight=0;setImageAlpha(255);invalidate();}});
        flightAnimator.start();
    }
    @Override protected void onDraw(Canvas canvas) {
        float offset=flight<.5f?flight*2:(flight*2-2)*.35f;
        int saved=canvas.save();canvas.translate((getLayoutDirection()==LAYOUT_DIRECTION_RTL?-1:1)*travel*offset,-travel*offset);super.onDraw(canvas);canvas.restoreToCount(saved);
    }
    @Override protected void onDetachedFromWindow() { if(flightAnimator!=null)flightAnimator.cancel();animate().cancel();super.onDetachedFromWindow(); }
}
