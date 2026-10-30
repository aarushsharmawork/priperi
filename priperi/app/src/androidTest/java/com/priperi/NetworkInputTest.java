package com.priperi;
import androidx.test.ext.junit.runners.AndroidJUnit4;import org.junit.*;import org.junit.runner.RunWith;import java.io.IOException;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class) public class NetworkInputTest {
 @Test public void realResponseParserRejectsHostileNestingAndInvalidUtf8(){assertThrows(IOException.class,()->Session.response(("{\"nested\":"+"[".repeat(12000)+"0"+"]".repeat(12000)+"}").getBytes(Crypto.UTF8)));assertThrows(IOException.class,()->Session.response(new byte[]{(byte)0xc3,0x28}));}
 @Test public void signedMalformedCiphertextCannotStarveTheNextInboxPage()throws Exception{PoisonInboxCase.run();}
 @Test public void validResponseAndQuotedBracesStillParse()throws Exception{assertEquals("[[{",Session.response("{\"data\":{\"text\":\"[[{\"}}".getBytes(Crypto.UTF8)).getJSONObject("data").getString("text"));}
}
