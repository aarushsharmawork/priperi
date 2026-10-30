package com.priperi;
import java.security.*;import java.nio.charset.StandardCharsets;import java.util.*;import javax.crypto.*;import javax.crypto.spec.*;
/** High-entropy offline recovery secret; authenticated versioned AES-256-GCM capsule. */
final class RecoveryCipher {
 static final int MAX=24000;private static final byte[] MAGIC="PRIPRK01".getBytes(StandardCharsets.US_ASCII);
 private static byte[] key(byte[] secret)throws Exception{if(secret==null||secret.length!=32)throw new GeneralSecurityException("Use the 24 recovery words.");MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update("priperi-recovery-file-key-v1\n".getBytes(StandardCharsets.US_ASCII));return hash.digest(secret);}
 static byte[] seal(byte[] plain,byte[] secret)throws Exception{if(plain.length<1||plain.length>MAX)throw new GeneralSecurityException("Recovery identity is too large.");byte[] iv=new byte[12],key=key(secret);new SecureRandom().nextBytes(iv);try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(MAGIC);byte[] encrypted=cipher.doFinal(plain),out=new byte[20+encrypted.length];System.arraycopy(MAGIC,0,out,0,8);System.arraycopy(iv,0,out,8,12);System.arraycopy(encrypted,0,out,20,encrypted.length);return out;}finally{Arrays.fill(key,(byte)0);}}
 static byte[] open(byte[] bytes,byte[] secret)throws Exception{if(bytes==null||bytes.length<37||bytes.length>MAX+36||!MessageDigest.isEqual(MAGIC,Arrays.copyOf(bytes,8)))throw new GeneralSecurityException("This is not a recovery backup.");byte[] key=key(secret);try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,bytes,8,12));cipher.updateAAD(MAGIC);return cipher.doFinal(bytes,20,bytes.length-20);}finally{Arrays.fill(key,(byte)0);}}
 private RecoveryCipher(){}
}
