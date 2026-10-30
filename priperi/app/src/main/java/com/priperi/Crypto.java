package com.priperi;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.google.crypto.tink.*;
import com.google.crypto.tink.hybrid.HybridConfig;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Standard Tink HPKE + sender signatures. No custom cipher or TLS overrides. */
public final class Crypto {
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
    private final String aesAlias, signAlias;
    private final KeyStore keys;
    public Crypto(String uid) throws Exception {
        HybridConfig.register();
        String suffix = hex(MessageDigest.getInstance("SHA-256").digest(uid.getBytes(UTF8)));
        aesAlias = "priperi.store." + suffix; signAlias = "priperi.sign." + suffix;
        keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
    }
    public void createKeys() throws Exception {
        if (!keys.containsAlias(aesAlias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(aesAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        if (!keys.containsAlias(signAlias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(signAlias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
    }
    public byte[] seal(byte[] plaintext, String purpose) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getKey(aesAlias, null)); cipher.updateAAD(purpose.getBytes(UTF8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(1); bytes.write(cipher.getIV()); bytes.write(cipher.doFinal(plaintext)); return bytes.toByteArray();
    }
    public byte[] open(byte[] ciphertext, String purpose) throws Exception {
        if (ciphertext.length < 30 || ciphertext[0] != 1) throw new GeneralSecurityException("Invalid encrypted record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keys.getKey(aesAlias, null), new GCMParameterSpec(128, ciphertext, 1, 12));
        cipher.updateAAD(purpose.getBytes(UTF8)); return cipher.doFinal(ciphertext, 13, ciphertext.length - 13);
    }
    public JSONObject newIdentity() throws Exception {
        KeysetHandle handle = KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"));
        ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(privateBytes));
        ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
        handle.getPublicKeysetHandle().writeNoSecret(JsonKeysetWriter.withOutputStream(publicBytes));
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String hpke = publicBytes.toString("UTF-8"), signing = b64(keys.getCertificate(signAlias).getPublicKey().getEncoded());
        return new JSONObject().put("private", b64(privateBytes.toByteArray())).put("hpke", hpke).put("signing", signing)
            .put("fingerprint", fingerprint(hpke, signing)).put("installation", java.util.UUID.randomUUID().toString())
            .put("deviceSecret", Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
    }
    public static String fingerprint(String hpke, String signing) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest((hpke + "|" + signing).getBytes(UTF8))); }
    static java.util.List<KeyDelegation.Certificate> certificates(org.json.JSONArray chain)throws Exception {
        java.util.List<KeyDelegation.Certificate> result=new java.util.ArrayList<>();if(chain==null)return result;if(chain.length()>KeyDelegation.MAX)throw new GeneralSecurityException("Too many account moves");for(int i=0;i<chain.length();i++){JSONObject c=chain.getJSONObject(i);if(c.length()!=2)throw new GeneralSecurityException("Invalid delegation");result.add(new KeyDelegation.Certificate(c.getString("key"),c.getString("signature")));}return result;
    }
    static void verifyProfile(JSONObject profile)throws Exception {String hpke=profile.getString("hpke");if(hpke.length()>4096)throw new GeneralSecurityException("Public key is too large");JsonBounds.check(hpke,32);if(profile.optJSONArray("delegations")!=null&&profile.getJSONArray("delegations").length()%2!=0)throw new GeneralSecurityException("Incomplete device delegation");if(!fingerprint(profile.getString("hpke"),profile.getString("signing")).equals(profile.getString("fingerprint")))throw new GeneralSecurityException("Invalid security key");KeyDelegation.keys(profile.getString("uid"),profile.getString("fingerprint"),profile.getString("signing"),certificates(profile.optJSONArray("delegations")));}
    static void verifySignature(JSONObject profile,byte[] value,String signature)throws Exception {verifyProfile(profile);KeyDelegation.verifyAuthorized(profile.getString("uid"),profile.getString("fingerprint"),profile.getString("signing"),certificates(profile.optJSONArray("delegations")),value,signature);}
    String publicSigning()throws Exception {return b64(keys.getCertificate(signAlias).getPublicKey().getEncoded());}
    String sign(byte[] value)throws Exception {return KeyDelegation.sign((java.security.PrivateKey)keys.getKey(signAlias,null),value);}
    JSONObject authorize(JSONObject identity,String owner,String child)throws Exception {
        java.util.List<String> allowed=KeyDelegation.keys(owner,identity.getString("fingerprint"),identity.getString("signing"),certificates(identity.optJSONArray("delegations")));String parent=allowed.get(allowed.size()-1);if(!parent.equals(publicSigning()))throw new GeneralSecurityException("The account signing key does not belong to this phone");return new JSONObject().put("key",child).put("signature",sign(KeyDelegation.context(owner,identity.getString("fingerprint"),parent,child).getBytes(UTF8)));
    }
    static void verifyPrivateIdentity(JSONObject identity)throws Exception {
        KeysetHandle handle=CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(unb64(identity.getString("private"))));ByteArrayOutputStream publicBytes=new ByteArrayOutputStream();handle.getPublicKeysetHandle().writeNoSecret(JsonKeysetWriter.withOutputStream(publicBytes));if(!publicBytes.toString("UTF-8").equals(identity.getString("hpke")))throw new GeneralSecurityException("The transfer contains a mismatched private key");
    }
    public String signDeviceReset(String uid, String nonce, JSONObject identity) throws Exception {
        String value = "priperi-reset-v1\n" + uid + "\n" + nonce + "\n" + identity.getString("installation") + "\n" + identity.getString("fingerprint");
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign((java.security.PrivateKey)keys.getKey(signAlias, null)); signature.update(value.getBytes(UTF8));
        return b64(signature.sign());
    }
    public static String context(JSONObject m) throws Exception {
        return (m.has("group")?"priperi-group-v1\n"+m.getString("group")+"\n"+m.getInt("groupVersion")+"\n":"priperi-v2\n") + m.getString("id") + "\n" + m.getString("sender") + "\n" + m.getString("recipient") + "\n" + m.getLong("sentAt") + "\n" + m.getString("senderFingerprint") + "\n" + m.getString("recipientFingerprint");
    }
    public JSONObject encrypt(String uid, JSONObject identity, JSONObject peer, String body) throws Exception {
        return encryptEnvelope(uid,identity,peer,body,java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),null,0);
    }
    JSONObject encryptGroup(String uid,JSONObject identity,JSONObject peer,String body,String id,long at,String group,int version)throws Exception {
        return encryptEnvelope(uid,identity,peer,body,id,at,group,version);
    }
    private JSONObject encryptEnvelope(String uid,JSONObject identity,JSONObject peer,String body,String id,long at,String group,int version)throws Exception {
        JSONObject m = new JSONObject().put("id",id).put("sender",uid).put("recipient",peer.getString("uid"))
            .put("sentAt",at).put("senderFingerprint",identity.getString("fingerprint")).put("recipientFingerprint",peer.getString("fingerprint"));
        if(group!=null)m.put("group",group).put("groupVersion",version);
        KeysetHandle publicKey = KeysetHandle.readNoSecret(JsonKeysetReader.withString(peer.getString("hpke")));
        String ciphertext = b64(publicKey.getPrimitive(HybridEncrypt.class).encrypt(body.getBytes(UTF8), context(m).getBytes(UTF8)));
        m.put("ciphertext", ciphertext);
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign((java.security.PrivateKey) keys.getKey(signAlias, null));
        signature.update((context(m) + "\n" + ciphertext).getBytes(UTF8)); m.put("signature", b64(signature.sign())); return m;
    }
    static final class SignedCiphertextInvalid extends GeneralSecurityException { SignedCiphertextInvalid(Throwable cause){super("Authenticated message ciphertext is unreadable",cause);} }
    public String decrypt(String uid, JSONObject identity, JSONObject peer, JSONObject m) throws Exception {
        if (!uid.equals(m.getString("recipient")) || !peer.getString("uid").equals(m.getString("sender"))
            || !identity.getString("fingerprint").equals(m.getString("recipientFingerprint")) || !peer.getString("fingerprint").equals(m.getString("senderFingerprint"))) throw new GeneralSecurityException("Key mismatch");
        verifySignature(peer,(context(m)+"\n"+m.getString("ciphertext")).getBytes(UTF8),m.getString("signature"));
        KeysetHandle privateKey = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(unb64(identity.getString("private"))));
        HybridDecrypt decryptor=privateKey.getPrimitive(HybridDecrypt.class);byte[] plain;
        try{plain=decryptor.decrypt(unb64(m.getString("ciphertext")),context(m).getBytes(UTF8));}
        catch(GeneralSecurityException|IllegalArgumentException error){throw new SignedCiphertextInvalid(error);}
        String body=new String(plain,UTF8);
        try{MessageContent.parse(body);}catch(IOException e){throw new MessageContent.InvalidContent(e);}return body;
    }
    byte[] signBackup(byte[] value)throws Exception {
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign((java.security.PrivateKey)keys.getKey(signAlias,null));signer.update(value);return signer.sign();
    }
    void verifyBackup(byte[] value,byte[] proof)throws Exception {
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initVerify(keys.getCertificate(signAlias).getPublicKey());signer.update(value);if(!signer.verify(proof))throw new GeneralSecurityException("Backup signature does not match this phone");
    }
    public void destroy() throws Exception { keys.deleteEntry(aesAlias); keys.deleteEntry(signAlias); }
    public static String b64(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
    public static byte[] unb64(String value) { return Base64.decode(value, Base64.NO_WRAP); }
    static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b)); return out.toString(); }
}
