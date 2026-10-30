package com.priperi;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class RecordCodecTest {
    private byte[] text(){return ("{\"body\":\""+"A private Unicode message: नमस्ते 世界. ".repeat(80)+"\"}").getBytes(StandardCharsets.UTF_8);}
    @Test public void usefulCompressionRoundTripsWithoutMakingSmallOrRandomRecordsLarger()throws Exception{
        byte[] data=text(),packed=RecordCodec.encode(data);assertTrue(packed.length<=data.length-RecordCodec.MIN_SAVING);assertArrayEquals(data,RecordCodec.decode(packed));
        byte[] small="{\"body\":\"hello\"}".getBytes(StandardCharsets.UTF_8);assertSame(small,RecordCodec.encode(small));assertSame(small,RecordCodec.decode(small));
        byte[] random=new byte[5000];new Random(1).nextBytes(random);assertSame(random,RecordCodec.encode(random));
    }
    @Test public void legacyUnicodeJsonIsReadWithoutMigration()throws Exception{byte[] old=text();assertSame(old,RecordCodec.decode(old));}
    @Test public void corruptionTruncationTrailingBytesAndUnknownVersionsFailClosed()throws Exception{
        byte[] packed=RecordCodec.encode(text());for(int i:new int[]{packed.length-1,packed.length/2}){byte[] bad=packed.clone();bad[i]^=1;assertThrows(IOException.class,()->RecordCodec.decode(bad));}
        assertThrows(IOException.class,()->RecordCodec.decode(Arrays.copyOf(packed,packed.length-1)));assertThrows(IOException.class,()->RecordCodec.decode(Arrays.copyOf(packed,packed.length+1)));
        byte[] version=packed.clone();version[3]=2;assertThrows(IOException.class,()->RecordCodec.decode(version));assertThrows(IOException.class,()->RecordCodec.decode(new byte[]{'P','R','Z',1}));
    }
    @Test public void declaredLengthsAndExpansionCannotExceedTheBound()throws Exception{
        byte[] packed=RecordCodec.encode(text());for(int n:new int[]{-1,0,1,RecordCodec.MAX_BYTES,RecordCodec.MAX_BYTES+1}){byte[] bad=packed.clone();ByteBuffer.wrap(bad,4,4).putInt(n);assertThrows(IOException.class,()->RecordCodec.decode(bad));}
        assertThrows(IOException.class,()->RecordCodec.encode(new byte[RecordCodec.MAX_BYTES+1]));assertThrows(IOException.class,()->RecordCodec.decode(new byte[RecordCodec.MAX_BYTES+1]));
        byte[] large=new byte[RecordCodec.MAX_BYTES];Arrays.fill(large,(byte)'x');assertArrayEquals(large,RecordCodec.decode(RecordCodec.encode(large)));
    }
    @Test public void dictionariesAndConcatenatedStreamsAreRejected()throws Exception{
        byte[] raw=text();Deflater z=new Deflater();z.setDictionary("A private Unicode message".getBytes(StandardCharsets.UTF_8));z.setInput(raw);z.finish();byte[] buffer=new byte[20000];int n=z.deflate(buffer);z.end();byte[] framed=ByteBuffer.allocate(n+8).put(new byte[]{'P','R','Z',1}).putInt(raw.length).put(buffer,0,n).array();assertThrows(IOException.class,()->RecordCodec.decode(framed));
        byte[] packed=RecordCodec.encode(raw),both=Arrays.copyOf(packed,packed.length*2-8);System.arraycopy(packed,8,both,packed.length,packed.length-8);assertThrows(IOException.class,()->RecordCodec.decode(both));
    }
    @Test public void worstCaseSizedGroupOutboxStaysReadableAndReportsRepresentativeSavings()throws Exception{
        byte[] random=new byte[12000];Random rng=new Random(7);StringBuilder json=new StringBuilder("{\"body\":\""+"A message with Unicode 世界 ".repeat(100)+"\",\"groupDeliveries\":[");
        for(int i=0;i<19;i++){rng.nextBytes(random);if(i>0)json.append(',');json.append("{\"ciphertext\":\"").append(Base64.getEncoder().encodeToString(random)).append("\"}");}
        byte[] record=json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        byte[] packed=RecordCodec.encode(record);assertArrayEquals(record,RecordCodec.decode(packed));assertTrue(packed.length<record.length);System.out.println("RECORD_SAMPLE raw="+record.length+" stored="+packed.length);
    }
}
