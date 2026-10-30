package com.priperi;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.security.*;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CryptoStorageTest {
    private Context context;
    private Vault alice, bob;
    private String aliceId, bobId;
    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext(); aliceId = UUID.randomUUID().toString(); bobId = UUID.randomUUID().toString();
        alice = new Vault(context, aliceId); bob = new Vault(context, bobId);
        TestIdentityTrust.pin(alice,profile(bobId, "bobby", bob)); TestIdentityTrust.pin(bob,profile(aliceId, "alice", alice));
    }
    private JSONObject profile(String uid, String name, Vault v) throws Exception {
        JSONObject identity = v.identity(); return new JSONObject().put("uid", uid).put("username", name).put("hpke", identity.getString("hpke")).put("signing", identity.getString("signing")).put("fingerprint", identity.getString("fingerprint"));
    }
    @After public void cleanup() throws Exception { if (alice != null) alice.destroy(); if (bob != null) bob.destroy(); }
    @Test public void hpkeRoundTripAuthenticatesSenderAndContext() throws Exception {
        String body = "A private message, café, नमस्ते.";
        JSONObject envelope = alice.crypto().encrypt(aliceId, alice.identity(), alice.contact(bobId), body);
        assertFalse(envelope.toString().contains(body));
        assertEquals(body, bob.crypto().decrypt(bobId, bob.identity(), bob.contact(aliceId), envelope));
        JSONObject tampered = new JSONObject(envelope.toString()).put("sentAt", envelope.getLong("sentAt") + 1);
        try { bob.crypto().decrypt(bobId, bob.identity(), bob.contact(aliceId), tampered); fail("Changed context accepted"); } catch (GeneralSecurityException expected) {}
        byte[] ciphertext = Crypto.unb64(envelope.getString("ciphertext")); ciphertext[ciphertext.length-1] ^= 1;
        JSONObject altered = new JSONObject(envelope.toString()).put("ciphertext", Crypto.b64(ciphertext));
        try { bob.crypto().decrypt(bobId, bob.identity(), bob.contact(aliceId), altered); fail("Changed ciphertext accepted"); } catch (GeneralSecurityException expected) {}
        try { alice.crypto().decrypt(aliceId, alice.identity(), alice.contact(bobId), envelope); fail("Wrong recipient accepted"); } catch (GeneralSecurityException expected) {}
    }
    @Test public void encryptedRecordsSurviveReopenAndDuplicateDelivery() throws Exception {
        String body = "TEST_PLAINTEXT_MUST_NEVER_APPEAR_ON_DISK_2026";
        JSONObject envelope = alice.crypto().encrypt(aliceId, alice.identity(), alice.contact(bobId), body);
        JSONObject record = new JSONObject().put("peer", aliceId).put("body", body).put("envelope", envelope).put("outgoing", false).put("state", "received");
        bob.store(record); bob.store(record); assertEquals(1, bob.messages(null).size()); assertTrue(bob.seen(envelope));
        String fingerprint = bob.identity().getString("fingerprint"); bob.close(); bob = new Vault(context, bobId);
        assertEquals(fingerprint, bob.identity().getString("fingerprint")); assertEquals(body, bob.messages(null).get(0).getString("body")); assertTrue(bob.seen(envelope));
        File dir = new File(context.getNoBackupFilesDir(), "account_" + Crypto.hex(MessageDigest.getInstance("SHA-256").digest(bobId.getBytes(Crypto.UTF8))));
        for (File file : dir.listFiles()) if (file.isFile()) assertFalse(file.getName(), new String(Files.readAllBytes(file.toPath()), Crypto.UTF8).contains(body));
        bob.clearHistory(null); assertEquals(0, bob.messages(null).size()); assertTrue(bob.seen(envelope));
        JSONObject conflict = new JSONObject(envelope.toString()).put("ciphertext", Crypto.b64(new byte[100]));
        try { bob.seen(conflict); fail("Conflicting replay accepted"); } catch (GeneralSecurityException expected) {}
    }
    @Test public void storageEncryptionRejectsTamperingAndCrossRecordSwaps() throws Exception {
        byte[] bytes = alice.crypto().seal("private".getBytes(Crypto.UTF8), "message:one");
        assertEquals("private", new String(alice.crypto().open(bytes, "message:one"), Crypto.UTF8));
        try { alice.crypto().open(bytes, "message:two"); fail("Record swap accepted"); } catch (GeneralSecurityException expected) {}
        bytes[bytes.length-1] ^= 1;
        try { alice.crypto().open(bytes, "message:one"); fail("Tampering accepted"); } catch (GeneralSecurityException expected) {}
    }
    @Test public void freshGcmNoncesAndKeystoreKeysRemainNonexportable()throws Exception {
        java.util.Set<String> nonces=new java.util.HashSet<>();
        for(int i=0;i<128;i++){byte[] sealed=alice.crypto().seal("same plaintext".getBytes(Crypto.UTF8),"nonce-regression");assertTrue("AES-GCM nonce reused",nonces.add(Crypto.b64(java.util.Arrays.copyOfRange(sealed,1,13))));assertEquals("same plaintext",new String(alice.crypto().open(sealed,"nonce-regression"),Crypto.UTF8));}
        byte[] sealed=alice.crypto().seal("private".getBytes(Crypto.UTF8),"same-purpose");assertThrows(GeneralSecurityException.class,()->bob.crypto().open(sealed,"same-purpose"));
        java.lang.reflect.Field field=Crypto.class.getDeclaredField("keys"),aes=Crypto.class.getDeclaredField("aesAlias"),signing=Crypto.class.getDeclaredField("signAlias");field.setAccessible(true);aes.setAccessible(true);signing.setAccessible(true);KeyStore keys=(KeyStore)field.get(alice.crypto());assertNull(keys.getKey((String)aes.get(alice.crypto()),null).getEncoded());assertNull(keys.getKey((String)signing.get(alice.crypto()),null).getEncoded());
    }
    @Test public void identityChangeIsRejectedAndDeletingDestroysKeys() throws Exception {
        JSONObject impostor = profile(bobId, "bobby", alice);
        try { TestIdentityTrust.pin(alice,impostor); fail("Identity replacement accepted"); } catch (GeneralSecurityException expected) {}
        Crypto original = bob.crypto(); byte[] saved = original.seal("private".getBytes(Crypto.UTF8), "deletion-test");
        bob.destroy(); bob = null;
        try { original.open(saved, "deletion-test"); fail("Deleted key remained usable"); } catch (Exception expected) {}
    }
}
