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

/** Explicitly opt-in: creates and removes only its own synthetic hosted accounts. */
@RunWith(AndroidJUnit4.class)
public class LiveRelayTest {
    interface Action { void run() throws Exception; }
    private void denied(int status, Action action) throws Exception {
        try { action.run(); fail("Access unexpectedly allowed"); }
        catch (Session.BackendException error) { assertEquals(status,error.status); }
    }
    @Test public void hostedAuthAndEncryptedPhoneDelivery() throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("live")));
        assertEquals("https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay",BuildConfig.BACKEND_URL);
        Context context=ApplicationProvider.getApplicationContext();
        String suffix=UUID.randomUUID().toString().substring(0,8),aliceName="qa_a_"+suffix,bobName="qa_b_"+suffix;
        String password="Qa-"+UUID.randomUUID()+"!9",changedPassword="Next-"+UUID.randomUUID()+"!9";
        Vault alice=new Vault(context,"live_alice_"+suffix),bob=new Vault(context,"live_bob_"+suffix);
        boolean aliceDeleted=false,bobDeleted=false;
        String[] aliceLogin={aliceName,password};
        try {
            String aliceId=Session.continueAccount(alice,aliceName,password).getString("uid");
            String bobId=Session.continueAccount(bob,bobName,password).getString("uid");
            String authEndpoint=new java.net.URL(Session.endpoint()).getProtocol()+"://"+new java.net.URL(Session.endpoint()).getAuthority()+"/auth/v1/token?grant_type=password";
            denied(400,()->Session.request(authEndpoint,new JSONObject().put("email",aliceId+"@accounts.priperi.invalid").put("password",password),null,false));
            Session.restore(alice);TestIdentityTrust.pin(alice,Repository.lookup(alice,bobName));
            Session.restore(bob);TestIdentityTrust.pin(bob,Repository.lookup(bob,aliceName));
            Session.restore(alice);
            String originalToken=Session.access(alice);
            denied(401,()->Session.request(Session.endpoint(),new JSONObject().put("operation","getAccount").put("deviceSecret",alice.identity().getString("deviceSecret")),"forged",true));
            denied(403,()->Session.request(Session.endpoint(),new JSONObject().put("operation","getAccount").put("deviceSecret",bob.identity().getString("deviceSecret")),originalToken,true));
            String text="Hosted encrypted delivery: नमस्ते, café.";
            JSONObject record=Repository.send(alice,bobId,text),envelope=record.getJSONObject("envelope");String id=envelope.getString("id");
            String cipher=envelope.getString("ciphertext");
            JSONObject altered=new JSONObject(envelope.toString()).put("ciphertext",(cipher.startsWith("A")?"B":"A")+cipher.substring(1));
            denied(403,()->Repository.call(alice,"sendMessage",new JSONObject().put("message",altered)));
            assertEquals("",Repository.sync(alice));assertEquals("sent",alice.message(id).getString("state"));
            Session.restore(bob);assertEquals(1,Repository.call(bob,"syncInbox",new JSONObject()).getJSONArray("messages").length());
            assertEquals("",Repository.sync(bob));assertEquals(text,bob.message(id).getString("body"));assertTrue(bob.seen(envelope));
            assertEquals(0,Repository.call(bob,"syncInbox",new JSONObject()).getJSONArray("messages").length());
            Session.restore(alice);assertEquals("",Repository.sync(alice));assertEquals("delivered",alice.message(id).getString("state"));
            assertEquals("delivered",Repository.call(alice,"sendMessage",new JSONObject().put("message",envelope)).getString("state"));
            Session.restore(bob);Repository.call(bob,"setBlocked",new JSONObject().put("uid",aliceId).put("blocked",true));
            Session.restore(alice);denied(403,()->Repository.call(alice,"sendMessage",new JSONObject().put("message",envelope)));
            Session.restore(bob);Repository.call(bob,"setBlocked",new JSONObject().put("uid",aliceId).put("blocked",false));
            Session.restore(alice);JSONObject session=alice.session();session.put("expires_at",1);alice.session(session);
            assertEquals(aliceId,Repository.call(alice,"getAccount",new JSONObject()).getString("uid"));
            String revoked=Session.access(alice);Session.signOut(alice);
            denied(403,()->Session.request(Session.endpoint(),new JSONObject().put("operation","getAccount").put("deviceSecret",alice.identity().getString("deviceSecret")),revoked,true));
            denied(401,()->Session.continueAccount(alice,aliceName,"Wrong-Password-2026!"));
            Session.continueAccount(alice,aliceName,password);
            String renamed="qa_new_"+suffix;Repository.call(alice,"renameAccount",new JSONObject().put("username",renamed));
            aliceLogin[0]=renamed;
            Repository.call(alice,"changePassword",new JSONObject().put("password",changedPassword));
            aliceLogin[1]=changedPassword;
            Session.continueAccount(alice,renamed,changedPassword);
            Session.signOut(alice);Session.continueAccount(alice,renamed,changedPassword);
            assertEquals("deleted",Repository.call(alice,"deleteAccount",new JSONObject()).getString("status"));aliceDeleted=true;
            assertEquals("deleted",Repository.call(alice,"deleteAccount",new JSONObject()).getString("status"));
            denied(403,()->Repository.call(alice,"getAccount",new JSONObject()));
            denied(401,()->Session.continueAccount(alice,renamed,changedPassword));
            Session.restore(bob);assertEquals("deleted",Repository.call(bob,"deleteAccount",new JSONObject()).getString("status"));bobDeleted=true;
        } finally {
            try { if(!aliceDeleted&&alice.account()!=null){Session.continueAccount(alice,aliceLogin[0],aliceLogin[1]);Repository.call(alice,"deleteAccount",new JSONObject());} }
            finally { try {if(!bobDeleted&&bob.account()!=null){Session.continueAccount(bob,bobName,password);Repository.call(bob,"deleteAccount",new JSONObject());}} finally {Session.forget();alice.destroy();bob.destroy();} }
        }
    }
}
