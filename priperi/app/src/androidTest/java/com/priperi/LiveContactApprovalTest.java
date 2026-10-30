package com.priperi;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in: creates and erases only two independently named synthetic hosted accounts. */
@RunWith(AndroidJUnit4.class)
public class LiveContactApprovalTest {
    @Test public void hostedCapRequiresRecipientApprovalEvenAfterDelivery()throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("liveapproval")));
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertEquals("https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay",BuildConfig.BACKEND_URL);
        Context context=ApplicationProvider.getApplicationContext();String suffix=UUID.randomUUID().toString().substring(0,8),password="Qa-Approval-"+UUID.randomUUID()+"!9";
        Vault a=new Vault(context,"live_approval_a_"+suffix),b=new Vault(context,"live_approval_b_"+suffix);
        String[] names={"qa_ac_a_"+suffix,"qa_ac_b_"+suffix};
        try {
            String aid=Session.continueAccount(a,names[0],password).getString("uid"),bid=Session.continueAccount(b,names[1],password).getString("uid");
            Session.restore(a);TestIdentityTrust.pin(a,Repository.call(a,"lookupUser",new JSONObject().put("uid",bid)));Session.restore(b);TestIdentityTrust.pin(b,Repository.call(b,"lookupUser",new JSONObject().put("uid",aid)));
            Session.restore(a);for(int i=0;i<3;i++)Repository.send(a,bid,"Hosted introduction "+i);Repository.sync(a);
            JSONObject bypass=a.crypto().encrypt(aid,a.identity(),a.contact(bid),"Fourth must be rejected");
            try{Repository.call(a,"sendMessage",new JSONObject().put("message",bypass));fail("Hosted cap bypassed");}catch(Session.BackendException expected){assertEquals(409,expected.status);assertEquals("contact-approval-required",expected.reason);}
            Session.restore(b);Repository.sync(b);assertEquals(3,b.messages(aid).size());assertEquals(0,Repository.call(b,"syncInbox",new JSONObject()).getJSONArray("messages").length());
            String fingerprint=a.contact(bid).getString("fingerprint");names[1]="qa_ac_new_"+suffix;Repository.call(b,"renameAccount",new JSONObject().put("username",names[1]));b.account(b.account().put("username",names[1]));
            Session.restore(a);Repository.sync(a);assertEquals(names[1],a.contact(bid).getString("username"));assertEquals(fingerprint,a.contact(bid).getString("fingerprint"));
            Session.restore(a);try{Repository.call(a,"sendMessage",new JSONObject().put("message",bypass));fail("Acknowledging reset the cap");}catch(Session.BackendException expected){assertEquals("contact-approval-required",expected.reason);}
            try{Repository.approveContact(a,bid);fail("Sender approved itself");}catch(Session.BackendException expected){assertEquals(409,expected.status);}
            Session.restore(b);Repository.approveContact(b,aid);Session.restore(a);Repository.sync(a);assertTrue(a.contact(bid).getJSONObject("contactState").getBoolean("approved"));
            Repository.send(a,bid,"Approved fourth message");Repository.sync(a);Session.restore(b);Repository.sync(b);assertEquals(4,b.messages(aid).size());
            Repository.call(b,"setBlocked",new JSONObject().put("uid",aid).put("blocked",true));Session.restore(a);try{Repository.call(a,"sendMessage",new JSONObject().put("message",bypass));fail("Approval bypassed block");}catch(Session.BackendException expected){assertEquals(403,expected.status);}
        } finally {
            try{if(a.account()!=null){assertEquals(names[0],a.account().getString("username"));Session.restore(a);Repository.call(a,"deleteAccount",new JSONObject());}}finally{try{if(b.account()!=null){assertEquals(names[1],b.account().getString("username"));Session.restore(b);Repository.call(b,"deleteAccount",new JSONObject());}}finally{a.destroy();b.destroy();Session.forget();}}
        }
    }
}
