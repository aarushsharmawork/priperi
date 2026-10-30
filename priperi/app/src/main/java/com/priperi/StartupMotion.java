package com.priperi;
import android.animation.ValueAnimator;import android.content.Context;import android.graphics.*;import android.view.View;
/** Drawn monogram while actual startup work is pending; no artificial launch delay. */
final class StartupMotion extends View {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path mark=new Path(),trace=new Path();private final PathMeasure measure=new PathMeasure();private float phase=1;private ValueAnimator animator;
 StartupMotion(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
 @Override protected void onSizeChanged(int w,int h,int ow,int oh){mark.reset();mark.moveTo(38,91);mark.lineTo(38,29);mark.lineTo(65,29);mark.cubicTo(97,29,97,72,65,72);mark.lineTo(38,72);mark.moveTo(50,88);mark.lineTo(68,88);measure.setPath(mark,false);}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int save=canvas.save();float scale=Math.min(getWidth(),getHeight())/128f;canvas.translate((getWidth()-128*scale)/2,(getHeight()-128*scale)/2);canvas.scale(scale,scale);paint.setColor(getContext().getColor(R.color.accent));paint.setStrokeWidth(3);paint.setAlpha(35);canvas.drawRoundRect(15,13,113,111,28,28,paint);canvas.drawPath(mark,paint);paint.setAlpha(255);trace.reset();measure.getSegment(0,measure.getLength()*Math.min(1,phase*1.4f),trace,true);canvas.drawPath(trace,paint);paint.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++){float pulse=(float)(.5+.5*Math.sin(phase*Math.PI*2-i*.7));paint.setAlpha(70+Math.round(150*pulse));canvas.drawCircle(51+i*13,111,2+phase*.4f,paint);}paint.setStyle(Paint.Style.STROKE);canvas.restoreToCount(save);}
 private void update(){if(!isAttachedToWindow()||getWindowVisibility()!=VISIBLE||!isShown()){stop();return;}if(animator==null&&ValueAnimator.areAnimatorsEnabled()){animator=ValueAnimator.ofFloat(0,1);animator.setDuration(1600);animator.setRepeatCount(ValueAnimator.INFINITE);animator.addUpdateListener(a->{phase=(float)a.getAnimatedValue();invalidate();});animator.start();}}
 private void stop(){if(animator!=null){animator.cancel();animator=null;}}
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();update();}
 @Override protected void onWindowVisibilityChanged(int v){super.onWindowVisibilityChanged(v);update();}
 @Override protected void onVisibilityChanged(View changed,int v){super.onVisibilityChanged(changed,v);update();}
 @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
}
