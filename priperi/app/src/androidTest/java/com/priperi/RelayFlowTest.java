package com.priperi;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

/** Uses real phone keys, HTTP, SQL and relay logic against the isolated fixture. */
@RunWith(AndroidJUnit4.class)
public class RelayFlowTest {
    // Keep multi-account probes on the same worker used by the app. Delayed UI
    // cleanup from an earlier test must not restore a different Session mid-probe.
    @Rule public final org.junit.rules.TestRule serialized=(base,description)->new org.junit.runners.model.Statement(){
        @Override public void evaluate()throws Throwable {
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);Throwable[] failure={null};
            Repository.local(ApplicationProvider.getApplicationContext(),v->{try{base.evaluate();}catch(Throwable e){failure[0]=e;}return true;},ok->done.countDown(),message->{failure[0]=new AssertionError(message);done.countDown();});
            assertTrue(done.await(120,java.util.concurrent.TimeUnit.SECONDS));if(failure[0]!=null)throw failure[0];
        }
    };
    private Vault alice,bob;
    private String aliceName,bobName,aliceId,bobId;
    private final String password="Strong-Password-2026!";
    @Before public void setup() throws Exception {
        assertEquals("http://10.0.2.2:5001/functions/v1/relay",BuildConfig.BACKEND_URL);
        Context context=ApplicationProvider.getApplicationContext();String suffix=UUID.randomUUID().toString().substring(0,8);
        aliceName="alice_"+suffix;bobName="bobby_"+suffix;
        alice=new Vault(context,"relay_alice_"+suffix);bob=new Vault(context,"relay_bob_"+suffix);
        aliceId=Session.continueAccount(alice,aliceName,password).getString("uid");
        bobId=Session.continueAccount(bob,bobName,password).getString("uid");
        Session.restore(alice);TestIdentityTrust.pin(alice,Repository.lookup(alice,bobName));
        Session.restore(bob);TestIdentityTrust.pin(bob,Repository.lookup(bob,aliceName));
    }
    @After public void cleanup() throws Exception {Session.forget();if(alice!=null)alice.destroy();if(bob!=null)bob.destroy();}
    @Test public void actualPhoneKeysDeliverSaveAcknowledgeAndSuppressReplay() throws Exception {
        Session.restore(alice);String text="Café, नमस्ते. This stays on the phones.";
        JSONObject outgoing=Repository.send(alice,bobId,text),envelope=outgoing.getJSONObject("envelope");String id=envelope.getString("id");
        assertEquals("",Repository.sync(alice));assertEquals("sent",alice.message(id).getString("state"));
        Session.restore(bob);assertEquals("",Repository.sync(bob));assertTrue(bob.seen(envelope));assertEquals(text,bob.message(id).getString("body"));
        assertEquals(0,Repository.call(bob,"syncInbox",new JSONObject()).getJSONArray("messages").length());
        Session.restore(alice);assertEquals("",Repository.sync(alice));assertEquals("delivered",alice.message(id).getString("state"));
        assertEquals("delivered",Repository.call(alice,"sendMessage",new JSONObject().put("message",envelope)).getString("state"));
        Session.restore(bob);assertEquals("",Repository.sync(bob));assertEquals(1,bob.messages(null).size());
    }
    @Test public void signOutRefreshWrongPasswordRenameAndDeletionRespectTheInstallation() throws Exception {
        Session.restore(alice);JSONObject session=alice.session();session.put("expires_at",1);alice.session(session);
        assertEquals(aliceId,Repository.call(alice,"getAccount",new JSONObject()).getString("uid"));assertTrue(alice.session().getLong("expires_at")>System.currentTimeMillis()/1000L);
        Session.signOut(alice);assertNull(Session.uid());assertNull(alice.session());
        try{Session.continueAccount(alice,aliceName,"Wrong-Password-2026!");fail("Wrong password accepted");}catch(Session.BackendException expected){assertEquals(401,expected.status);}
        assertEquals(aliceId,Session.continueAccount(alice,aliceName,password).getString("uid"));
        String changed="new_"+aliceName;Repository.call(alice,"renameAccount",new JSONObject().put("username",changed));
        Session.signOut(alice);Session.continueAccount(alice,changed,password);
        Repository.call(alice,"changePassword",new JSONObject().put("password","Changed-Password-2026!"));
        Session.signOut(alice);Session.continueAccount(alice,changed,"Changed-Password-2026!");
        assertEquals("deleted",Repository.call(alice,"deleteAccount",new JSONObject()).getString("status"));
        assertEquals("deleted",Repository.call(alice,"deleteAccount",new JSONObject()).getString("status"));
        try{Session.continueAccount(alice,changed,"Changed-Password-2026!");fail("A deleted account was silently recreated");}catch(Session.BackendException expected){assertEquals(401,expected.status);}
        try{Repository.call(alice,"getAccount",new JSONObject());fail("Deleted account retained access");}catch(Session.BackendException expected){assertEquals(403,expected.status);}
    }
    @Test public void laterReceiptsAreCheckedWhenOverAHundredEarlierMessagesStayUndelivered() throws Exception {
        Session.restore(alice);Repository.send(alice,bobId,"Please approve this stress-test contact.");Repository.sync(alice);
        Session.restore(bob);Repository.sync(bob);Repository.approveContact(bob,aliceId);
        Session.restore(alice);Repository.sync(alice);
        for(int i=0;i<100;i++) {
            JSONObject record=Repository.send(alice,bobId,"Earlier undelivered message "+i);
            record.put("state","sent");alice.store(record);
        }
        JSONObject latest=Repository.send(alice,bobId,"This later message arrived.");
        String id=latest.getJSONObject("envelope").getString("id");Repository.sync(alice);
        Session.restore(bob);Repository.sync(bob);assertNotNull(bob.message(id));
        Session.restore(alice);Repository.sync(alice);Repository.sync(alice);
        assertEquals("delivered",alice.message(id).getString("state"));
    }
    @Test public void batchesStayDurableAndAnUnavailableOutboxContactDoesNotBlockReceiving() throws Exception {
        Session.restore(alice);
        for(int i=0;i<3;i++)Repository.send(alice,bobId,"Batched message "+i);
        Repository.sync(alice);Session.restore(bob);Repository.sync(bob);
        assertEquals(3,bob.messages(null).size());
        assertEquals(0,Repository.call(bob,"syncInbox",new JSONObject()).getJSONArray("messages").length());
        Repository.send(bob,aliceId,"This contact is about to be removed.");
        Context context=ApplicationProvider.getApplicationContext();
        try(Vault eve=new Vault(context,"receive_first_"+UUID.randomUUID())) {
            String eveName="qa_receive_"+UUID.randomUUID().toString().substring(0,8);
            Session.continueAccount(eve,eveName,password);TestIdentityTrust.pin(eve,Repository.lookup(eve,bobName));
            JSONObject incoming=Repository.send(eve,bobId,"Receive this despite the unavailable outbox.");Repository.sync(eve);
            Session.restore(alice);Repository.call(alice,"deleteAccount",new JSONObject());Session.restore(bob);
            assertTrue(Repository.sync(bob).contains("saved and will retry"));
            assertEquals("Receive this despite the unavailable outbox.",bob.message(incoming.getJSONObject("envelope").getString("id")).getString("body"));
            assertEquals(0,Repository.call(bob,"syncInbox",new JSONObject()).getJSONArray("messages").length());
            Session.restore(eve);Repository.call(eve,"deleteAccount",new JSONObject());Session.restore(bob);
        }
    }
}
