package com.priperi;
import java.security.*;import java.security.interfaces.ECPublicKey;import java.security.spec.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Bounded P-256 authorisations anchored to the pinned original account key. */
final class KeyDelegation {
 static final int MAX=32;
 private static final Map<String,List<String>> VERIFIED=new LinkedHashMap<>();
 static final class Certificate {final String key,signature;Certificate(String key,String signature){this.key=key;this.signature=signature;}}
 static String context(String uid,String fingerprint,String parent,String child){return String.join("\n","priperi-key-delegation-v1",uid,fingerprint,parent,child);}
 static PublicKey publicKey(String value)throws GeneralSecurityException{
  if(value==null||value.length()<80||value.length()>160)throw new GeneralSecurityException("Invalid signing key");byte[] bytes;try{bytes=Base64.getDecoder().decode(value);}catch(IllegalArgumentException e){throw new GeneralSecurityException("Invalid signing key",e);}
  if(!Base64.getEncoder().encodeToString(bytes).equals(value))throw new GeneralSecurityException("Invalid signing key");PublicKey k=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));if(!MessageDigest.isEqual(k.getEncoded(),bytes)||!(k instanceof ECPublicKey))throw new GeneralSecurityException("Invalid signing algorithm");
  AlgorithmParameters params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));ECParameterSpec expected=params.getParameterSpec(ECParameterSpec.class),actual=((ECPublicKey)k).getParams();if(!expected.getCurve().equals(actual.getCurve())||!expected.getGenerator().equals(actual.getGenerator())||!expected.getOrder().equals(actual.getOrder())||expected.getCofactor()!=actual.getCofactor())throw new GeneralSecurityException("Unsupported signing curve");return k;
 }
 static List<String> keys(String uid,String fingerprint,String root,List<Certificate> chain)throws GeneralSecurityException{
  if(uid==null||!uid.matches("[a-f0-9-]{36}")||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")||chain.size()>MAX)throw new GeneralSecurityException("Invalid account delegation");if(root==null||!root.matches("[A-Za-z0-9+/]{80,158}={0,2}"))throw new GeneralSecurityException("Invalid signing key");for(Certificate c:chain)if(c==null||c.key==null||c.signature==null||c.key.length()>160||c.signature.length()>144||!c.key.matches("[A-Za-z0-9+/]{80,158}={0,2}")||!c.signature.matches("[A-Za-z0-9+/]{80,142}={0,2}"))throw new GeneralSecurityException("Invalid delegation encoding");String cacheKey=TransferCipher.hash((uid+"\n"+fingerprint+"\n"+root+"\n"+digest(chain)).getBytes(StandardCharsets.UTF_8));synchronized(VERIFIED){List<String> saved=VERIFIED.get(cacheKey);if(saved!=null)return new ArrayList<>(saved);}publicKey(root);List<String> result=new ArrayList<>();result.add(root);
  for(Certificate c:chain){publicKey(c.key);if(result.contains(c.key)||!verify(result.get(result.size()-1),context(uid,fingerprint,result.get(result.size()-1),c.key).getBytes(StandardCharsets.UTF_8),c.signature))throw new GeneralSecurityException("The transferred account key could not be verified");result.add(c.key);}synchronized(VERIFIED){if(VERIFIED.size()>=128)VERIFIED.remove(VERIFIED.keySet().iterator().next());VERIFIED.put(cacheKey,new ArrayList<>(result));}return result;
 }
 static boolean verify(String key,byte[] value,String encoded)throws GeneralSecurityException{
  if(encoded==null||encoded.length()<80||encoded.length()>144)return false;byte[] signature;try{signature=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){return false;}if(!Base64.getEncoder().encodeToString(signature).equals(encoded))return false;Signature verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(publicKey(key));verifier.update(value);try{return verifier.verify(signature);}catch(SignatureException e){return false;}
 }
 static void verifyAuthorized(String uid,String fp,String root,List<Certificate> chain,byte[] value,String signature)throws GeneralSecurityException{if(chain.size()%2!=0)throw new GeneralSecurityException("Incomplete device delegation");List<String> allowed=keys(uid,fp,root,chain);for(int i=allowed.size()-1;i>=0;i-=2)if(verify(allowed.get(i),value,signature))return;throw new GeneralSecurityException("Invalid account signature");}
 static String sign(PrivateKey key,byte[] value)throws GeneralSecurityException{Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(key);signature.update(value);return Base64.getEncoder().encodeToString(signature.sign());}
 static KeyPair temporaryKey()throws GeneralSecurityException{KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));return generator.generateKeyPair();}
 static String publicValue(KeyPair key){return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());}
 static String digest(List<Certificate> chain)throws GeneralSecurityException{StringJoiner parts=new StringJoiner("\n");for(Certificate c:chain)parts.add(c.key+"\n"+c.signature);return TransferCipher.hash(parts.toString().getBytes(StandardCharsets.UTF_8));}
 static void clearCache(){synchronized(VERIFIED){VERIFIED.clear();}}
 private KeyDelegation(){}
}
