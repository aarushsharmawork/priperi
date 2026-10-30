package com.priperi;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

/** A small shared dock. Navigation never supplies authentication flags. */
final class AppNavigation {
    interface Select {void open(String section);}
    final LinearLayout view;
    private final Activity activity;private final LinearLayout[] items=new LinearLayout[3];private final TextView[] labels=new TextView[3];private final ImageView[] icons=new ImageView[3];
    private final FrameLayout track;private final LinearLayout buttons;private final View indicator;private int selectedIndex;
    private final String[] keys={"chats","groups","settings"};
    AppNavigation(Activity host,LinearLayout parent,String selected,Select action){
        activity=host;view=Ui.row(host);view.setGravity(Gravity.CENTER);view.setPadding(Ui.dp(host,6),Ui.dp(host,6),Ui.dp(host,6),Ui.dp(host,6));
        GradientDrawable background=Ui.shape(host,host.getColor(R.color.surface),25);background.setStroke(Ui.dp(host,1),host.getColor(R.color.line));view.setBackground(background);
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,-2);outer.setMargins(Ui.dp(host,16),Ui.dp(host,8),Ui.dp(host,16),Ui.dp(host,10));parent.addView(view,outer);
        track=new FrameLayout(host);buttons=Ui.row(host);indicator=new View(host);indicator.setBackground(Ui.shape(host,host.getColor(R.color.soft),19));track.addView(indicator,new FrameLayout.LayoutParams(0,0));track.addView(buttons,new FrameLayout.LayoutParams(-1,-2));view.addView(track,new LinearLayout.LayoutParams(-1,-2));track.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)position(false);});
        String[] names={"All chats","Groups","Settings"},symbols={"chat","group","settings"};
        for(int i=0;i<3;i++){final String key=keys[i];LinearLayout item=Ui.column(host);items[i]=item;item.setGravity(Gravity.CENTER);item.setPadding(Ui.dp(host,5),Ui.dp(host,8),Ui.dp(host,5),Ui.dp(host,7));item.setMinimumHeight(Ui.dp(host,54));
            icons[i]=Ui.symbol(host,symbols[i],23,R.color.muted);item.addView(icons[i]);labels[i]=Ui.text(host,names[i],11,R.color.muted);labels[i].setMaxLines(2);labels[i].setGravity(Gravity.CENTER);item.addView(labels[i]);item.setContentDescription(names[i]);item.setFocusable(true);item.setFilterTouchesWhenObscured(true);item.setOnClickListener(v->{Ui.tap(item);action.open(key);});buttons.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
        select(selected,false);
    }
    void select(String key,boolean animate){for(int i=0;i<3;i++){boolean on=keys[i].equals(key);if(on)selectedIndex=i;items[i].setSelected(on);icons[i].setImageDrawable(new Icons(i==0?"chat":i==1?"group":"settings",activity.getColor(on?R.color.accent:R.color.muted)));labels[i].setTextColor(activity.getColor(on?R.color.accent:R.color.muted));items[i].setBackground(new RippleDrawable(ColorStateList.valueOf(activity.getColor(R.color.soft)),Ui.shape(activity,android.graphics.Color.TRANSPARENT,19),null));}track.post(()->position(animate));}
    private void position(boolean animate){int width=track.getWidth()/3;if(width<=0)return;FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)indicator.getLayoutParams();if(params.width!=width||params.height!=buttons.getHeight()){params.width=width;params.height=buttons.getHeight();indicator.setLayoutParams(params);}float x=selectedIndex*width;if(animate&&android.animation.ValueAnimator.areAnimatorsEnabled())indicator.animate().translationX(x).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();else{indicator.animate().cancel();indicator.setTranslationX(x);}}
    void requests(int count){labels[1].setText(count>0?"Groups · "+(count>99?"99+":count):"Groups");items[1].setContentDescription(count>0?"Groups. "+count+" requests":"Groups");}
    static void navigate(Activity activity,String section){
        Class<?> target="settings".equals(section)?SettingsActivity.class:InboxActivity.class;
        activity.startActivity(new Intent(activity,target).putExtra("section",section).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        if(activity instanceof SettingsActivity&&target==InboxActivity.class)activity.finish();
    }
}
