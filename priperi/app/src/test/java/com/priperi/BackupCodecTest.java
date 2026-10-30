package com.priperi;

import org.junit.Test;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public class BackupCodecTest {
    private static final String UID="00000000-0000-4000-8000-000000000001",FP="f".repeat(64);
    private final byte[] key=new byte[32],salt=new byte[16];
    private final KeyPair pair;
    public BackupCodecTest()throws Exception{KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(256);pair=gen.generateKeyPair();new SecureRandom().nextBytes(key);new SecureRandom().nextBytes(salt);}
    private byte[] sign(byte[] value)throws Exception{Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(pair.getPrivate());s.update(value);return s.sign();}
    private void verify(byte[] value,byte[] proof)throws Exception{Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(pair.getPublic());s.update(value);if(!s.verify(proof))throw new GeneralSecurityException();}
    private byte[] archive(byte[] record)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();BackupCodec.write(out,key,salt,UID,FP,c->c.accept(record),this::sign,()->{});return out.toByteArray();}
    private int read(byte[] bytes,byte[] chosenKey)throws Exception{return BackupCodec.read(new ByteArrayInputStream(bytes),chosenKey,UID,FP,null,this::verify,()->{});}
    @Test public void segmentedRoundTripChecksSignatureBeforeUseAndHidesMessageText()throws Exception{
        byte[] message=("Confidential message! "+"x".repeat(12000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] data=archive(message);assertFalse(new String(data,java.nio.charset.StandardCharsets.ISO_8859_1).contains("Confidential message!"));assertEquals(1,read(data,key));List<byte[]> decoded=new ArrayList<>();BackupCodec.read(new ByteArrayInputStream(data),key,UID,FP,v->decoded.add(v.clone()),this::verify,()->{});assertArrayEquals(message,decoded.get(0));assertFalse(Arrays.equals(data,archive(message)));
    }
    @Test public void wrongPasswordKeysTamperingTruncationAndTrailingBytesAreRejected()throws Exception{
        byte[] data=archive(new byte[6000]),wrong=key.clone();wrong[0]^=1;assertThrows(Exception.class,()->read(data,wrong));
        for(int index:new int[]{0,12,50,data.length-1}){byte[] changed=data.clone();changed[index]^=1;assertThrows(Exception.class,()->read(changed,key));}
        assertThrows(Exception.class,()->read(Arrays.copyOf(data,data.length-1),key));assertThrows(Exception.class,()->read(Arrays.copyOf(data,data.length+1),key));
    }
    @Test public void aPasswordHolderCannotSubstituteAccountPhoneSignatureOrOversizedFrames()throws Exception{
        byte[] data=archive(new byte[10]);assertThrows(Exception.class,()->BackupCodec.read(new ByteArrayInputStream(data),key,"different-account",FP,null,this::verify,()->{}));
        assertThrows(Exception.class,()->BackupCodec.read(new ByteArrayInputStream(data),key,UID,FP,null,(v,p)->{throw new GeneralSecurityException();},()->{}));
        assertThrows(IOException.class,()->archive(new byte[BackupCodec.MAX_RECORD+1]));assertFalse(BackupCodec.password("short".toCharArray()));
    }
    @Test public void passwordDerivationIsFixedCostSaltedAndReproducible()throws Exception{
        char[] password="four independent random words".toCharArray();byte[] salt=new byte[16];byte[] a=BackupCodec.key(password,salt),b=BackupCodec.key(password,salt);assertEquals(32,a.length);assertArrayEquals(a,b);salt[0]=1;assertFalse(Arrays.equals(a,BackupCodec.key(password,salt)));assertEquals(600000,BackupCodec.ITERATIONS);
    }
}
