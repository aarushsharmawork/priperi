package com.priperi;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.*;
import android.widget.LinearLayout;

/** Public cosmetic entry point. It grants no authentication and never forwards caller extras. */
public final class SplashActivity extends GuardedActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long shownAt;private boolean resumed,routed;
    private final Runnable next=()->{
        if(!resumed||routed||environmentBlocked||isFinishing()||isDestroyed())return;
        routed=true;startActivity(new Intent(this,MainActivity.class));finish();
    };
    @Override protected void onCreate(Bundle state){super.onCreate(state);shownAt=SystemClock.elapsedRealtime();LinearLayout root=Ui.page(this);root.addView(Ui.loading(this,""),new LinearLayout.LayoutParams(-1,-1));}
    @Override protected void onResume(){super.onResume();resumed=true;if(environmentBlocked)return;long brief=ValueAnimator.areAnimatorsEnabled()?240:0;handler.postDelayed(next,Math.max(0,brief-(SystemClock.elapsedRealtime()-shownAt)));}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(next);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
