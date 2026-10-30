package com.priperi;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Woven paper and broad contour lines; cached local drawing, never a downloaded wallpaper. */
final class ConversationSurface extends Drawable {
    private final Paint dots=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int background;
    private final float density;
    private final Path contours=new Path(),grain=new Path();
    ConversationSurface(Context context){background=context.getColor(R.color.page);dots.setColor(context.getColor(R.color.muted));dots.setAlpha(24);density=context.getResources().getDisplayMetrics().density;}
    @Override protected void onBoundsChange(Rect b){contours.reset();grain.reset();float tile=180*density;for(float y=b.top-tile;y<b.bottom;y+=tile)for(float x=b.left-tile;x<b.right;x+=tile){contours.addArc(x,y,x+tile*1.6f,y+tile*1.6f,10,105);contours.addArc(x+tile*.15f,y+tile*.15f,x+tile*1.45f,y+tile*1.45f,10,105);}float step=18*density;for(float y=b.top+step/2;y<b.bottom;y+=step)for(float x=b.left+step/2;x<b.right;x+=step)grain.addCircle(x+(y/step%2)*3*density,y,.35f*density,Path.Direction.CW);}
    @Override public void draw(Canvas canvas){canvas.drawColor(background);dots.setStyle(Paint.Style.STROKE);dots.setStrokeWidth(.6f*density);dots.setAlpha(13);canvas.drawPath(contours,dots);dots.setStyle(Paint.Style.FILL);dots.setAlpha(20);canvas.drawPath(grain,dots);}
    @Override public void setAlpha(int alpha){dots.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){dots.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
