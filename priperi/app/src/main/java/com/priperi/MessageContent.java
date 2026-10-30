package com.priperi;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Versioned content INSIDE the existing signed HPKE envelope, never a new cipher. */
final class MessageContent {
    static final String MAGIC="\u001epriperi:",PREFIX=MAGIC+"1:";
    static final class InvalidContent extends java.security.GeneralSecurityException {InvalidContent(Throwable cause){super("Unsupported message content",cause);}}
    static final long EDIT_WINDOW=15*60_000L,DELETE_WINDOW=24*60*60_000L;
    final int kind;final String text,replyTo,target;final long targetAt;final int groupVersion;
    private MessageContent(int k,String s,String r,String t,long at,int version){kind=k;text=s;replyTo=r;target=t;targetAt=at;groupVersion=version;}
    static String text(String value,String reply)throws IOException {
        if(!Validation.message(value))throw new IOException("Write a message of up to 4,000 characters.");
        if(reply==null&&!value.startsWith(MAGIC))return value;
        return encode(new MessageContent(1,value,reply,null,0,0));
    }
    static String change(boolean delete,String target,long at,int version,String text)throws IOException {return encode(new MessageContent(delete?3:2,delete?"":text,null,target,at,version));}
    static boolean allowed(int kind,long original,long changed){return original>=0&&changed>=original&&changed-original<=(kind==2?EDIT_WINDOW:DELETE_WINDOW);}
    private static void uuid(DataOutputStream out,String value)throws IOException {try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}catch(RuntimeException e){throw new IOException("Invalid message reference");}}
    private static String uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong()).toString();}
    private static String encode(MessageContent m)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(m.kind);
        if(m.kind==1){out.writeBoolean(m.replyTo!=null);if(m.replyTo!=null)uuid(out,m.replyTo);}else{uuid(out,m.target);if(m.targetAt<0||m.groupVersion<0)throw new IOException("Invalid message reference");out.writeLong(m.targetAt);out.writeInt(m.groupVersion);}
        if(m.kind!=3){if(!Validation.message(m.text))throw new IOException("Write a message of up to 4,000 characters.");byte[] body=m.text.getBytes(StandardCharsets.UTF_8);out.writeInt(body.length);out.write(body);}
        if(bytes.size()>16384)throw new IOException("This message is too large.");return PREFIX+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    static MessageContent parse(String value)throws IOException {
        if(value==null)throw new IOException("Invalid message");
        if(value.startsWith(MAGIC)&&!value.startsWith(PREFIX))throw new IOException("Unknown content version");
        if(!value.startsWith(PREFIX)){if(!Validation.message(value))throw new IOException("Invalid message");return new MessageContent(1,value,null,null,0,0);}
        if(value.length()>22000)throw new IOException("Invalid message size");
        String encoded=value.substring(PREFIX.length());byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw new IOException("Invalid message frame");}
        if(bytes.length>16384||!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw new IOException("Invalid message frame");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){int kind=in.readUnsignedByte();String reply=null,target=null,text="";long at=0;int version=0;
            if(kind==1){int flag=in.readUnsignedByte();if(flag>1)throw new IOException("Invalid reply");if(flag==1)reply=uuid(in);}else if(kind==2||kind==3){target=uuid(in);at=in.readLong();version=in.readInt();if(at<0||version<0)throw new IOException("Invalid reference");}else throw new IOException("Unknown message type");
            if(kind!=3){int length=in.readInt();if(length<1||length>12000||length>in.available())throw new IOException("Invalid message size");byte[] body=new byte[length];in.readFully(body);try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();}catch(CharacterCodingException e){throw new IOException("Invalid message text");}if(!Validation.message(text))throw new IOException("Invalid message text");}
            if(in.available()!=0)throw new IOException("Trailing message content");return new MessageContent(kind,text,reply,target,at,version);
        }
    }
}
