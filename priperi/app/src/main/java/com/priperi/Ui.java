package com.priperi;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;

final class Ui {
    private Ui() {}
    static com.google.android.material.dialog.MaterialAlertDialogBuilder dialog(Activity activity){return new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity){@Override public androidx.appcompat.app.AlertDialog create(){androidx.appcompat.app.AlertDialog dialog=super.create();DeviceSecurity.secureWindow(dialog.getWindow());if(activity instanceof GuardedActivity)((GuardedActivity)activity).guardDialog(dialog);return dialog;}}.setBackground(shape(activity,activity.getColor(R.color.surface),24));}
    static LinearLayout loading(Activity activity,String message){
        LinearLayout panel=column(activity);panel.setGravity(Gravity.CENTER);pad(activity,panel,24);panel.setBackgroundColor(color(activity,R.color.page));panel.setTag("branded-loading");
        StartupMotion motion=new StartupMotion(activity);panel.addView(motion,new LinearLayout.LayoutParams(dp(activity,116),dp(activity,116)));
        TextView brand=title(activity,"Priperi",25);brand.setGravity(Gravity.CENTER);panel.addView(brand);space(activity,panel,12);
        TextView label=text(activity,message,14,R.color.muted);label.setGravity(Gravity.CENTER);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);panel.addView(label,new LinearLayout.LayoutParams(-1,-2));
        if(activity.getResources().getConfiguration().screenHeightDp>=450){space(activity,panel,20);for(int i=0;i<3;i++){LinearLayout bubble=column(activity);bubble.setBackground(shape(activity,color(activity,R.color.surface),16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(activity,i==1?142:194),dp(activity,28));lp.gravity=i==1?Gravity.END:Gravity.START;lp.setMargins(dp(activity,14),dp(activity,5),dp(activity,14),dp(activity,5));panel.addView(bubble,lp);}}
        return panel;
    }
    static int dp(Activity a,int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    static int color(Activity a,int id){return a.getColor(id);}
    static GradientDrawable shape(Activity a,int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(a,radius));return bg;}
    static LinearLayout column(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static LinearLayout page(Activity a){
        WindowCompat.setDecorFitsSystemWindows(a.getWindow(),false);DeviceSecurity.secureWindow(a);
        boolean light=(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat c=WindowCompat.getInsetsController(a.getWindow(),a.getWindow().getDecorView());c.setAppearanceLightStatusBars(light);c.setAppearanceLightNavigationBars(light);
        LinearLayout root=column(a);root.setBackgroundColor(color(a,R.color.page));root.setFilterTouchesWhenObscured(true);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets safe=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());
            int width=v.getWidth()>0?v.getWidth():a.getResources().getDisplayMetrics().widthPixels;
            int gutter=Math.max(0,(width-safe.left-safe.right-dp(a,720))/2);
            v.setPadding(safe.left+gutter,safe.top,safe.right+gutter,safe.bottom);return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)ViewCompat.requestApplyInsets(v);});
        a.setContentView(root);return root;
    }
    static TextView text(Activity a,String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color(a,color));t.setFontFeatureSettings("kern");t.setLineSpacing(dp(a,2),1);return t;}
    static TextView title(Activity a,String value,int size){TextView t=text(a,value,size,R.color.ink);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static void space(Activity a,LinearLayout parent,int height){parent.addView(new View(a),new LinearLayout.LayoutParams(1,dp(a,height)));}
    static void pad(Activity a,View view,int amount){int d=dp(a,amount);view.setPadding(d,d,d,d);}
    static LinearLayout scroll(Activity a,LinearLayout parent){
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        LinearLayout body=new LinearLayout(a){@Override protected void onMeasure(int width,int height){int size=MeasureSpec.getSize(width);super.onMeasure(MeasureSpec.makeMeasureSpec(Math.min(size,dp(a,600)),MeasureSpec.EXACTLY),height);}};
        body.setOrientation(LinearLayout.VERTICAL);pad(a,body,a.getResources().getConfiguration().screenWidthDp<360?14:20);
        scroll.addView(body,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));parent.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return body;
    }
    static MaterialButton button(Activity a,String label,boolean primary,View.OnClickListener action){
        MaterialButton b=new MaterialButton(a,null,primary?com.google.android.material.R.attr.materialButtonStyle:android.R.attr.borderlessButtonStyle);
        b.setText(label);b.setAllCaps(false);b.setLetterSpacing(.01f);b.setTextSize(15);b.setCornerRadius(dp(a,16));b.setMinHeight(dp(a,52));b.setOnClickListener(action);b.setFilterTouchesWhenObscured(true);
        if(primary){b.setBackgroundTintList(ColorStateList.valueOf(color(a,R.color.accent)));b.setTextColor(color(a,R.color.on_accent));}else b.setTextColor(color(a,R.color.accent));return b;
    }
    static ImageView symbol(Activity a,String icon,int size,int tint){ImageView image=new ImageView(a);image.setImageDrawable(new Icons(icon,color(a,tint)));image.setLayoutParams(new LinearLayout.LayoutParams(dp(a,size),dp(a,size)));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return image;}
    static ImageButton iconButton(Activity a,String icon,String label,View.OnClickListener action){ImageButton button=new ImageButton(a);button.setImageDrawable(new Icons(icon,color(a,R.color.ink)));button.setScaleType(ImageView.ScaleType.FIT_CENTER);button.setContentDescription(label);pad(a,button,12);button.setLayoutParams(new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));button.setBackground(new RippleDrawable(ColorStateList.valueOf(color(a,R.color.soft)),shape(a,android.graphics.Color.TRANSPARENT,24),null));button.setOnClickListener(action);button.setFilterTouchesWhenObscured(true);return button;}
    static TextView avatar(Activity a,String name,int size){String value=name.isEmpty()?"?":name.substring(0,1).toUpperCase(java.util.Locale.ROOT);TextView avatar=text(a,value,size/3,R.color.accent);avatar.setTypeface(null,Typeface.BOLD);avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(a,color(a,R.color.soft),size/3));avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(a,size),dp(a,size)));avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return avatar;}
    static LinearLayout card(Activity a){LinearLayout card=column(a);pad(a,card,20);GradientDrawable background=shape(a,color(a,R.color.surface),20);background.setStroke(dp(a,1),color(a,R.color.line));card.setBackground(background);return card;}
    static void fadeIn(Activity a,View view){if(ValueAnimator.areAnimatorsEnabled()){view.setAlpha(0);view.setTranslationY(dp(a,8));view.animate().alpha(1).translationY(0).setDuration(180).start();}}
    static void tap(View view){if(ValueAnimator.areAnimatorsEnabled())view.animate().scaleX(.96f).scaleY(.96f).setDuration(70).withEndAction(()->view.animate().scaleX(1).scaleY(1).setDuration(140).start()).start();}
    static void lift(View view,boolean selected){view.animate().cancel();if(ValueAnimator.areAnimatorsEnabled())view.animate().scaleX(selected?1.025f:1).scaleY(selected?1.025f:1).translationZ(selected?view.getResources().getDisplayMetrics().density*7:0).setDuration(160).start();else{view.setScaleX(1);view.setScaleY(1);view.setTranslationZ(0);}}
    static EditText search(Activity a,LinearLayout parent,String label){
        LinearLayout bar=row(a);bar.setPadding(dp(a,14),0,dp(a,2),0);bar.setBackground(shape(a,color(a,R.color.page),24));bar.addView(symbol(a,"search",21,R.color.muted));EditText edit=new EditText(a);edit.setHint("Search");edit.setContentDescription(label);edit.setTextSize(16);edit.setTextColor(color(a,R.color.ink));edit.setHintTextColor(color(a,R.color.muted));edit.setSingleLine(true);edit.setBackground(null);edit.setSaveEnabled(false);edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});edit.setPadding(dp(a,10),dp(a,12),0,dp(a,12));bar.addView(edit,new LinearLayout.LayoutParams(0,-2,1));ImageButton clear=iconButton(a,"close","Clear search",v->edit.setText(""));clear.setVisibility(View.GONE);bar.addView(clear);edit.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){clear.setVisibility(s.length()>0?View.VISIBLE:View.GONE);}});parent.addView(bar,new LinearLayout.LayoutParams(-1,-2));space(a,parent,12);return edit;
    }
    static EditText input(Activity a,LinearLayout body,String label,int type){
        TextInputLayout wrapper=new TextInputLayout(a);wrapper.setHint(label);wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);wrapper.setBoxCornerRadii(dp(a,14),dp(a,14),dp(a,14),dp(a,14));
        TextInputEditText edit=new TextInputEditText(a);edit.setTextSize(16);edit.setTextColor(color(a,R.color.ink));edit.setInputType(type);edit.setSingleLine(true);edit.setSaveEnabled(false);edit.setFilterTouchesWhenObscured(true);
        boolean password=(type&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD;
        edit.setImportantForAutofill(password?View.IMPORTANT_FOR_AUTOFILL_YES:View.IMPORTANT_FOR_AUTOFILL_NO);
        if(password){wrapper.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);edit.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);}else edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        wrapper.addView(edit,new LinearLayout.LayoutParams(-1,-2));body.addView(wrapper,new LinearLayout.LayoutParams(-1,-2));space(a,body,14);return edit;
    }
    static String value(EditText edit){return edit.getText().toString().trim();}
    static TextView status(Activity a,LinearLayout parent){TextView text=text(a,"",13,R.color.muted);text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);parent.addView(text);return text;}
    static LinearLayout header(Activity a,LinearLayout root,String title,String actionLabel,View.OnClickListener action){LinearLayout bar=row(a);bar.setPadding(dp(a,20),dp(a,8),dp(a,12),dp(a,8));TextView heading=title(a,title,24);bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));if(actionLabel!=null)bar.addView(button(a,actionLabel,false,action));root.addView(bar);return bar;}
    static void section(Activity a,LinearLayout body,String label){space(a,body,24);TextView text=text(a,label,13,R.color.accent);text.setTypeface(null,Typeface.BOLD);body.addView(text);space(a,body,10);}
    static TextView item(Activity a,LinearLayout body,String title,String subtitle,View.OnClickListener action){
        LinearLayout row=row(a);row.setPadding(dp(a,14),dp(a,14),dp(a,12),dp(a,14));row.setBackground(new RippleDrawable(ColorStateList.valueOf(color(a,R.color.soft)),shape(a,color(a,R.color.surface),14),null));
        String icon=title.contains("backup")?"backup":title.contains("password")?"key":title.contains("lock")?"lock":title.contains("Notifications")?"bell":title.equals("Appearance")?"sun":title.equals("Username")?"person":title.contains("Delete")||title.contains("Clear")?"trash":title.contains("key")?"shield":"chat";
        row.addView(symbol(a,icon,22,R.color.accent));LinearLayout words=column(a);words.setPadding(dp(a,16),0,dp(a,8),0);TextView t=text(a,title,16,R.color.ink);t.setTypeface(null,Typeface.BOLD);words.addView(t);if(!subtitle.isEmpty()){space(a,words,4);words.addView(text(a,subtitle,13,R.color.muted));}row.addView(words,new LinearLayout.LayoutParams(0,-2,1));row.addView(symbol(a,"chevron",16,R.color.muted));row.setOnClickListener(action);row.setFocusable(true);row.setFilterTouchesWhenObscured(true);row.setContentDescription(title+". "+subtitle);body.addView(row,new LinearLayout.LayoutParams(-1,-2));space(a,body,3);return t;
    }
    static String time(long millis){return new java.text.SimpleDateFormat("h:mm a",java.util.Locale.getDefault()).format(new java.util.Date(millis));}
    static String chatTime(long millis){if(millis<=0)return "";java.util.Calendar now=java.util.Calendar.getInstance(),then=java.util.Calendar.getInstance();then.setTimeInMillis(millis);return now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR)&&now.get(java.util.Calendar.DAY_OF_YEAR)==then.get(java.util.Calendar.DAY_OF_YEAR)?time(millis):new java.text.SimpleDateFormat("d MMM",java.util.Locale.getDefault()).format(new java.util.Date(millis));}
}
