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

/** Runs against the isolated fixture by default; live project requires -e deviceLive yes. */
@RunWith(AndroidJUnit4.class)
public class DeviceFlowTest {
    @Test public void stableDeviceLoginSearchRenameAndDestructiveKeyReset() throws Exception {
        boolean local=BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:");
        assumeTrue(local||"yes".equals(InstrumentationRegistry.getArguments().getString("deviceLive")));
        Context context=ApplicationProvider.getApplicationContext();String suffix=UUID.randomUUID().toString().substring(0,8),aliceName="qa_device_"+suffix,bobName="qa_peer_"+suffix,password="Device-"+UUID.randomUUID()+"!9";
        String namespace="device_alice_"+suffix;Vault alice=new Vault(context,namespace),bob=new Vault(context,"device_bob_"+suffix);boolean aliceDeleted=false,bobDeleted=false;
        try {
            String stable=alice.identity().getString("deviceId");assertEquals(stable,DeviceIdentity.value(context,namespace));assertNotEquals(stable,bob.identity().getString("deviceId"));
            assertFalse(Session.deviceStatus(alice).getBoolean("registered"));
            String aliceId=Session.continueAccount(alice,aliceName,password).getString("uid"),bobId=Session.continueAccount(bob,bobName,password).getString("uid");
            assertTrue(Session.deviceStatus(alice).getBoolean("registered"));Session.restore(alice);TestIdentityTrust.pin(alice,Repository.lookup(alice,bobName));
            JSONObject message=Repository.send(alice,bobId,"Reset should remove this queued message.");Repository.sync(alice);assertEquals("sent",alice.message(message.getJSONObject("envelope").getString("id")).getString("state"));
            String renamed="qa_changed_"+suffix;Repository.call(alice,"renameAccount",new JSONObject().put("username",renamed));Session.signOut(alice);
            assertEquals(aliceId,Session.continueAccount(alice,null,password).getString("uid"));assertEquals(renamed,alice.account().getString("username"));
            Session.restore(bob);assertEquals(aliceId,Repository.search(bob,"qa_changed_").getJSONObject(0).getString("uid"));
            Session.restore(alice);Session.signOut(alice);Session.resetAccount(alice);aliceDeleted=true;
            // Retrying the same durable request succeeds after deletion, without any tombstone.
            Session.resetAccount(alice);assertFalse(Session.deviceStatus(alice).getBoolean("registered"));
            assertNotNull(alice.message(message.getJSONObject("envelope").getString("id"))); // Phone not erased before confirmation.
            alice.destroy();alice=new Vault(context,namespace);assertEquals(stable,alice.identity().getString("deviceId"));assertEquals(0,alice.messages(null).size());
            Session.restore(bob);assertEquals(0,Repository.call(bob,"syncInbox",new JSONObject()).getJSONArray("messages").length());assertEquals(0,Repository.search(bob,renamed).length());
            String replacement=Session.continueAccount(alice,aliceName,password).getString("uid");aliceDeleted=false;assertNotEquals(aliceId,replacement);
            Repository.call(alice,"deleteAccount",new JSONObject());aliceDeleted=true;Session.restore(bob);Repository.call(bob,"deleteAccount",new JSONObject());bobDeleted=true;
        } finally {
            boolean safeAlice=aliceDeleted||alice.account()==null,safeBob=bobDeleted||bob.account()==null;
            try {if(!safeAlice){Session.resetAccount(alice);safeAlice=true;}}finally{
                try {if(!safeBob){Session.resetAccount(bob);safeBob=true;}}finally{Session.forget();if(safeAlice)alice.destroy();else alice.close();if(safeBob)bob.destroy();else bob.close();}
            }
        }
    }
}
