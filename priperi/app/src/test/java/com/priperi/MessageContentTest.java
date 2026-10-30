package com.priperi;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public class MessageContentTest {
 private final String id=UUID.randomUUID().toString();
 @Test public void plainUnicodeAndMagicTextRoundTrip()throws Exception{for(String value:new String[]{"Hello","🌱".repeat(2000),MessageContent.PREFIX+"not a command"}){MessageContent m=MessageContent.parse(MessageContent.text(value,null));assertEquals(1,m.kind);assertEquals(value,m.text);}}
 @Test public void replyCarriesOnlyAnIdAndCurrentText()throws Exception{MessageContent m=MessageContent.parse(MessageContent.text("Reply",id));assertEquals(id,m.replyTo);assertEquals("Reply",m.text);assertNull(m.target);}
 @Test public void editAndDeleteCarryBoundedTargetsAndNoDeleteText()throws Exception{for(boolean delete:new boolean[]{false,true}){MessageContent m=MessageContent.parse(MessageContent.change(delete,id,123,2,"New text"));assertEquals(delete?3:2,m.kind);assertEquals(id,m.target);assertEquals(123,m.targetAt);assertEquals(2,m.groupVersion);assertEquals(delete?"":"New text",m.text);}}
 @Test public void malformedOversizeUnknownAndTrailingFramesFail()throws Exception{for(String value:new String[]{"",MessageContent.PREFIX+"!!!",MessageContent.MAGIC+"2:AA==",MessageContent.PREFIX+"A".repeat(23000),MessageContent.PREFIX+Base64.getEncoder().encodeToString(new byte[]{4}),MessageContent.text("hello",id)+"AAAA"})assertThrows(IOException.class,()->MessageContent.parse(value));assertThrows(IOException.class,()->MessageContent.text("a".repeat(4001),id));assertThrows(IOException.class,()->MessageContent.change(false,"bad",0,0,"hello"));}
 @Test public void windowsRejectFutureBackdatedAndExpiredChanges(){assertTrue(MessageContent.allowed(2,100,100));assertTrue(MessageContent.allowed(2,100,900100));assertFalse(MessageContent.allowed(2,100,900101));assertFalse(MessageContent.allowed(3,100,99));assertFalse(MessageContent.allowed(3,-1,100));assertFalse(MessageContent.allowed(3,0,Long.MAX_VALUE));}
}
