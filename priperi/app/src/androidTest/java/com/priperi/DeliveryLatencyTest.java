package com.priperi;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeliveryLatencyTest {
    @Test public void foregroundPollShowsAndDurablyAcknowledgesWithoutManualRefresh() throws Exception {
        assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        Context context=ApplicationProvider.getApplicationContext();String suffix=UUID.randomUUID().toString().substring(0,8);
        Vault sender=new Vault(context,"latency_sender_"+suffix);JSONObject[] receiver={null};String[] token={null};
        Repository.reset();
        local(context,v->{if(v.account()!=null){if(!v.account().optString("username").startsWith("qa_notice_"))throw new SecurityException("Disposable installation required");Session.resetAccount(v);Repository.eraseLocal(v);}return true;});
        try {
            local(context,v->{if(v.account()!=null)throw new SecurityException("Disposable installation required");receiver[0]=Session.continueAccount(v,"qa_latency_"+suffix,"Latency-Password-2026!");Session.continueAccount(sender,"qa_fast_"+suffix,"Latency-Password-2026!");token[0]=sender.session().getString("access_token");Session.restore(v);return true;});
            try(ActivityScenario<InboxActivity> scenario=ActivityScenario.launch(InboxActivity.class)) {
                waitText(scenario,"New chat",10000);
                // Use the independent peer's signed request, without changing the
                // foreground account's in-process Session during the measurement.
                String message="Latency probe "+suffix;
                JSONObject env=sender.crypto().encrypt(sender.account().getString("uid"),sender.identity(),receiver[0],message);
                long start=SystemClock.elapsedRealtime();
                Session.request(Session.endpoint(),new JSONObject().put("operation","sendMessage").put("deviceSecret",sender.identity().getString("deviceSecret")).put("message",env),token[0],true);
                waitText(scenario,message,10000);
                long elapsed=SystemClock.elapsedRealtime()-start;
                System.out.println("FOREGROUND_DELIVERY_MS="+elapsed);
                assertTrue("A normal foreground delivery still waited near the old 15s interval",elapsed<10000);
                local(context,v->{if(!v.seen(env))throw new SecurityException("Message was not durably saved");if(Repository.call(v,"syncInbox",new JSONObject()).getJSONArray("messages").length()!=0)throw new SecurityException("Message was not acknowledged");return true;});
            }
        } finally {
            local(context,v->{try{if(sender.account()!=null){Session.restore(sender);Session.resetAccount(sender);}}finally{Session.restore(v);}if(v.account()!=null){Session.resetAccount(v);Repository.eraseLocal(v);}return true;});sender.destroy();Repository.reset();
        }
    }
    private static void local(Context c,Repository.Job<Boolean> job)throws Exception {CountDownLatch done=new CountDownLatch(1);String[] error={null};Repository.local(c,job,ok->done.countDown(),e->{error[0]=e;done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));assertNull(error[0]);}
    private static boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text)&&v.isShown())return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;}return false;}
    private static void waitText(ActivityScenario<InboxActivity> scenario,String text,long ms){long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){boolean[] found={false};scenario.onActivity(a->found[0]=contains(a.getWindow().getDecorView(),text));if(found[0])return;SystemClock.sleep(50);}fail("Message did not appear: "+text);}
}
