package com.priperi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.*;

/** Local records only. The returned bytes must be authenticated/encrypted before storage. */
final class RecordCodec {
    static final int MAX_BYTES=1024*1024,MIN_BYTES=1024,MIN_SAVING=64;
    private RecordCodec() {}
    static byte[] encode(byte[] plain)throws IOException {
        if(plain.length>MAX_BYTES)throw new IOException("Message record is too large");
        if(plain.length<MIN_BYTES)return plain;
        Deflater compressor=new Deflater(Deflater.BEST_SPEED);byte[] buffer=new byte[4096];
        try{
            compressor.setInput(plain);compressor.finish();
            ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(plain.length,8192));
            out.write(new byte[]{'P','R','Z',1});out.write(ByteBuffer.allocate(4).putInt(plain.length).array());
            while(!compressor.finished()){
                int n=compressor.deflate(buffer);if(n==0&&!compressor.finished())return plain;
                if(out.size()+n>plain.length-MIN_SAVING)return plain;
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        }finally{compressor.end();Arrays.fill(buffer,(byte)0);}
    }
    static byte[] decode(byte[] encoded)throws IOException {
        if(encoded.length>MAX_BYTES)throw new IOException("Message record is too large");
        if(encoded.length<3||encoded[0]!='P'||encoded[1]!='R'||encoded[2]!='Z')return encoded; // Legacy JSON.
        if(encoded.length<8||encoded[3]!=1)throw new IOException("Unsupported message record");
        int length=ByteBuffer.wrap(encoded,4,4).getInt();if(length<1||length>MAX_BYTES)throw new IOException("Invalid message record size");
        Inflater inflater=new Inflater();byte[] plain=new byte[length],extra=new byte[1];boolean success=false;
        try{
            inflater.setInput(encoded,8,encoded.length-8);int count=0;
            while(!inflater.finished()){
                boolean full=count==length;int n=full?inflater.inflate(extra):inflater.inflate(plain,count,length-count);
                if(full&&n>0)throw new IOException("Message record exceeds its declared size");
                count+=n;if(n==0&&!inflater.finished())throw new IOException("Incomplete message record");
            }
            if(count!=length||inflater.getRemaining()!=0)throw new IOException("Invalid message record framing");
            success=true;return plain;
        }catch(DataFormatException error){throw new IOException("Invalid compressed message record",error);}
        finally{inflater.end();Arrays.fill(extra,(byte)0);if(!success)Arrays.fill(plain,(byte)0);}
    }
}
