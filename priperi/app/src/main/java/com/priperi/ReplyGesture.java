package com.priperi;

import android.animation.ValueAnimator;import android.view.*;import android.widget.ImageView;import java.util.function.BooleanSupplier;

/** Horizontal intent is claimed only after touch slop, leaving vertical history scrolling alone. */
final class ReplyGesture implements View.OnTouchListener {
 private final View bubble;private final ImageView mark;private final BooleanSupplier allowed;private final Runnable reply;
 private final float density,slop;private float x,y;private boolean swiping,vertical,armed;
 ReplyGesture(View bubble,ImageView mark,BooleanSupplier allowed,Runnable reply){this.bubble=bubble;this.mark=mark;this.allowed=allowed;this.reply=reply;density=bubble.getResources().getDisplayMetrics().density;slop=ViewConfiguration.get(bubble.getContext()).getScaledTouchSlop();}
 public boolean onTouch(View target,android.view.MotionEvent event){
  switch(event.getActionMasked()){
   case MotionEvent.ACTION_DOWN:x=event.getRawX();y=event.getRawY();swiping=false;vertical=false;armed=false;return false;
   case MotionEvent.ACTION_MOVE:
    float dx=event.getRawX()-x,dy=event.getRawY()-y;if(vertical||!allowed.getAsBoolean())return false;
    if(!swiping&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)){vertical=true;return false;}
    if(!swiping&&Math.abs(dx)>slop*1.5f&&Math.abs(dx)>Math.abs(dy)*1.5f){swiping=true;target.cancelLongPress();bubble.cancelLongPress();target.getParent().requestDisallowInterceptTouchEvent(true);}
    if(swiping){float drag=Math.max(-72*density,Math.min(72*density,dx*.75f));bubble.setTranslationX(drag);mark.setTranslationX(drag>0?-bubble.getWidth()/2f:bubble.getWidth()/2f);mark.setAlpha(Math.min(1,Math.abs(drag)/(52*density)));boolean ready=Math.abs(drag)>=52*density;if(ready&&!armed)target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);armed=ready;return true;}return false;
   case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
    boolean consumed=swiping,send=swiping&&armed&&event.getActionMasked()==MotionEvent.ACTION_UP&&allowed.getAsBoolean();swiping=false;armed=false;target.getParent().requestDisallowInterceptTouchEvent(false);
    if(consumed){target.cancelLongPress();if(ValueAnimator.areAnimatorsEnabled()){bubble.animate().translationX(0).setDuration(180).setInterpolator(new android.view.animation.OvershootInterpolator(.6f)).start();mark.animate().alpha(0).setDuration(140).start();}else{bubble.setTranslationX(0);mark.setAlpha(0);}if(send)reply.run();return true;}return false;
   default:return false;
  }
 }
}
