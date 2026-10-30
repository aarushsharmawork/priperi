package com.priperi;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small consistent line icons; no downloaded assets or emoji glyphs. */
final class Icons extends Drawable {
    private final String name; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Icons(String name, int color) { this.name=name; paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE); }
    @Override public void draw(Canvas canvas) {
        Rect b=getBounds(); canvas.save(); canvas.translate(b.left,b.top); canvas.scale(b.width()/24f,b.height()/24f);
        switch(name) {
            case "settings":canvas.drawCircle(12,12,3.5f,paint);for(int i=0;i<8;i++){double r=i*Math.PI/4;line(canvas,12+(float)Math.cos(r)*7,12+(float)Math.sin(r)*7,12+(float)Math.cos(r)*9,12+(float)Math.sin(r)*9);}canvas.drawCircle(12,12,7,paint);break;
            case "pin":path(canvas,8,3,16,3,15,10,19,14,5,14,9,10,8,3);line(canvas,12,14,12,22);break;
            case "copy":canvas.drawRoundRect(8,7,21,21,2,2,paint);path(canvas,16,7,16,3,3,3,3,16,8,16);break;
            case "unsend":canvas.drawCircle(12,12,9,paint);path(canvas,10,7,6,11,10,15);path(canvas,6,11,14,11,17,13,17,16,14,17);break;
            case "reply":path(canvas,10,5,3,11,10,17);path(canvas,3,11,13,11,19,14,21,20);break;
            case "nfc":canvas.drawRoundRect(7,4,17,20,3,3,paint);canvas.drawArc(3,5,11,19,130,100,false,paint);canvas.drawArc(13,5,21,19,-50,100,false,paint);line(canvas,10,16,10,8);line(canvas,10,8,14,16);line(canvas,14,16,14,8);break;
            case "group":canvas.drawCircle(9,8,3.5f,paint);canvas.drawArc(2,13,16,25,190,160,false,paint);canvas.drawArc(12,4,20,12,-90,170,false,paint);canvas.drawArc(14,13,23,24,230,110,false,paint);break;
            case "backup":canvas.drawArc(3,7,13,17,110,190,false,paint);canvas.drawArc(8,3,20,17,190,170,false,paint);canvas.drawArc(15,9,24,19,230,160,false,paint);line(canvas,5,18,9,18);line(canvas,18,18,20,18);path(canvas,9,15,13,11,17,15);line(canvas,13,11,13,22);break;
            case "qr":canvas.drawRect(3,3,9,9,paint);canvas.drawRect(15,3,21,9,paint);canvas.drawRect(3,15,9,21,paint);path(canvas,15,15,21,15,21,21,18,21,18,18,15,18);break;
            case "brand":path(canvas,6,21,6,4,13,4);canvas.drawArc(7,4,21,16,-90,180,false,paint);line(canvas,14,16,6,16);line(canvas,10,20,15,20);break;
            case "search": canvas.drawCircle(10.5f,10.5f,6.3f,paint); line(canvas,15.2f,15.2f,21,21); break;
            case "close": line(canvas,6,6,18,18);line(canvas,18,6,6,18);break;
            case "back": path(canvas,14,5,7,12,14,19);line(canvas,7,12,21,12);break;
            case "chevron":path(canvas,9,5,16,12,9,19);break;
            case "more":for(int y:new int[]{5,12,19})canvas.drawCircle(12,y,.9f,paint);break;
            case "send":path(canvas,4,4,21,12,4,20,7,12,4,4);line(canvas,7,12,21,12);break;
            case "edit":path(canvas,5,15,16,4,20,8,9,19,4,20,5,15);line(canvas,14,6,18,10);break;
            case "person":canvas.drawCircle(12,8,4,paint);canvas.drawArc(4,13,20,28,190,160,false,paint);break;
            case "key":canvas.drawCircle(8,9,4,paint);path(canvas,11,12,20,21,22,19,20,17,18,19);break;
            case "lock":canvas.drawRoundRect(5,10,19,21,3,3,paint);canvas.drawArc(8,3,16,15,180,180,false,paint);line(canvas,12,14,12,17);break;
            case "shield":path(canvas,12,2,21,6,20,15,17,19,12,22,7,19,4,15,3,6,12,2);path(canvas,8,12,11,15,17,9);break;
            case "bell-off":path(canvas,5,17,7,14,7,9,9,5,12,4,15,5,17,9,17,14,19,17,5,17);line(canvas,3,3,21,21);break;
            case "bell":path(canvas,5,17,7,14,7,9,9,5,12,4,15,5,17,9,17,14,19,17,5,17);canvas.drawArc(10,17,14,22,0,180,false,paint);break;
            case "sun":canvas.drawCircle(12,12,4,paint);for(int i=0;i<8;i++){double r=i*Math.PI/4;line(canvas,12+(float)Math.cos(r)*7,12+(float)Math.sin(r)*7,12+(float)Math.cos(r)*9,12+(float)Math.sin(r)*9);}break;
            case "trash":path(canvas,5,7,6,21,18,21,19,7);line(canvas,3,7,21,7);path(canvas,8,7,8,3,16,3,16,7);line(canvas,10,11,10,17);line(canvas,14,11,14,17);break;
            default:canvas.drawRoundRect(3,4,21,19,5,5,paint);path(canvas,6,19,5,23,11,19);line(canvas,7,9,17,9);line(canvas,7,13,14,13);
        }
        canvas.restore();
    }
    private void line(Canvas c,float x,float y,float endX,float endY){c.drawLine(x,y,endX,endY,paint);}
    private void path(Canvas c,float... xy){Path p=new Path();p.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)p.lineTo(xy[i],xy[i+1]);c.drawPath(p,paint);}
    @Override public int getIntrinsicWidth(){return 24;}
    @Override public int getIntrinsicHeight(){return 24;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
