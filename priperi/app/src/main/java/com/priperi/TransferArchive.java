package com.priperi;

import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.*;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Bounded offline transfer snapshot under a random AES-256 streaming key; no login tokens or signing private keys. */
final class TransferArchive {
    static final long MAX_BYTES=128L*1024*1024;
    static final int MAX_RECORD=2*1024*1024,MAX_RECORDS=100000,ITERATIONS=600000;
    private static final byte[] MAGIC="PRIPTH01".getBytes(StandardCharsets.US_ASCII);
    interface Check {void run()throws Exception;}
    interface Record {void accept(byte[] value)throws Exception;}
    interface Source {void each(Record record)throws Exception;}
    interface Signer {byte[] sign(byte[] value)throws Exception;}
    interface Verifier {void verify(byte[] value,byte[] signature)throws Exception;}
    static byte[] salt(){byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);return salt;}
    static byte[] header(InputStream input)throws IOException {
        byte[] bytes=new byte[24];new DataInputStream(input).readFully(bytes);
        if(!Arrays.equals(MAGIC,Arrays.copyOf(bytes,8)))throw new IOException("Not a supported Priperi transfer file.");return bytes;
    }
    static byte[] salt(byte[] header){return Arrays.copyOfRange(header,8,24);}
    private static byte[] header(byte[] salt)throws IOException {if(salt.length!=16)throw new IOException("Invalid transfer file salt");byte[] h=new byte[24];System.arraycopy(MAGIC,0,h,0,8);System.arraycopy(salt,0,h,8,16);return h;}
    private static StreamingAead cipher(byte[] key)throws Exception {return new AesGcmHkdfStreaming(key,"HmacSha256",32,4096,0);}
    private static byte[] binding(byte[] digest)throws IOException {ByteArrayOutputStream value=new ByteArrayOutputStream();value.write("priperi-signed-transfer-history-v1\n".getBytes(StandardCharsets.US_ASCII));value.write(digest);return value.toByteArray();}
    static int write(OutputStream target,byte[] key,byte[] salt,String uid,String fingerprint,Source source,Signer signer,Check check)throws Exception {
        byte[] header=header(salt);OutputStream limited=limited(target);limited.write(header);MessageDigest hash=MessageDigest.getInstance("SHA-256");int[] count={0};
        try(OutputStream encrypted=cipher(key).newEncryptingStream(limited,header);DigestOutputStream signed=new DigestOutputStream(encrypted,hash);DataOutputStream out=new DataOutputStream(signed)) {
            text(out,uid);text(out,fingerprint);out.writeLong(System.currentTimeMillis());
            source.each(value->{check.run();if(value.length==0||value.length>MAX_RECORD||++count[0]>MAX_RECORDS)throw new IOException("Transfer file limit exceeded");out.writeByte(1);out.writeInt(value.length);out.write(value);});
            out.writeByte(0);signed.on(false);byte[] signature=signer.sign(binding(hash.digest()));if(signature.length>128)throw new IOException("Invalid signature size");out.writeInt(signature.length);out.write(signature);check.run();
        }
        return count[0];
    }
    static int read(InputStream source,byte[] key,String uid,String fingerprint,Record record,Verifier verifier,Check check)throws Exception {
        InputStream limited=limited(source);byte[] header=header(limited);MessageDigest hash=MessageDigest.getInstance("SHA-256");int count=0;
        try(InputStream decrypted=cipher(key).newDecryptingStream(limited,header);DigestInputStream signed=new DigestInputStream(decrypted,hash);DataInputStream in=new DataInputStream(signed)) {
            if(!uid.equals(text(in,36))||!fingerprint.equals(text(in,64)))throw new GeneralSecurityException("Use this transfer file with the same registered account and phone.");
            if(in.readLong()<0)throw new IOException("Invalid transfer file time");
            for(;;){check.run();int type=in.readUnsignedByte();if(type==0)break;if(type!=1||++count>MAX_RECORDS)throw new IOException("Invalid transfer file record");int length=in.readInt();if(length<1||length>MAX_RECORD)throw new IOException("Invalid transfer file record size");byte[] value=new byte[length];in.readFully(value);try{if(record!=null)record.accept(value);}finally{Arrays.fill(value,(byte)0);}}
            signed.on(false);byte[] digest=hash.digest();int length=in.readInt();if(length<8||length>128)throw new IOException("Invalid transfer file signature");byte[] signature=new byte[length];in.readFully(signature);
            if(in.read()!=-1)throw new IOException("Trailing transfer file data");verifier.verify(binding(digest),signature);check.run();return count;
        }
    }
    static String utf8(byte[] bytes)throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw new IOException("Invalid transfer file text",e);}}
    private static void text(DataOutputStream out,String value)throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String text(DataInputStream in,int max)throws IOException {int n=in.readInt();if(n<1||n>max)throw new IOException("Invalid transfer file identity");byte[] bytes=new byte[n];in.readFully(bytes);return utf8(bytes);}
    static InputStream limited(InputStream in){return new FilterInputStream(in){long count;private void used(int n)throws IOException{if(n>0&&(count+=n)>MAX_BYTES)throw new IOException("Transfer file exceeds 128 MB");}public int read()throws IOException{int v=in.read();if(v>=0)used(1);return v;}public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);used(n);return n;}};}
    static OutputStream limited(OutputStream out){return new FilterOutputStream(out){long count;private void used(int n)throws IOException{if((count+=n)>MAX_BYTES)throw new IOException("Transfer file exceeds 128 MB");}public void write(int v)throws IOException{used(1);out.write(v);}public void write(byte[] b,int off,int len)throws IOException{used(len);out.write(b,off,len);}};}
    private TransferArchive(){}
}
