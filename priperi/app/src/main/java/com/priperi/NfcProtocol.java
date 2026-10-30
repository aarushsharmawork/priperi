package com.priperi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small, public-identity-only protocol. Never selects payment or system applications. */
final class NfcProtocol {
    static final String AID="F05052495045524901";
    private static final byte[] SELECT={0,(byte)0xA4,4,0,9,(byte)0xF0,0x50,0x52,0x49,0x50,0x45,0x52,0x49,1,0};
    private static final byte[] READ={(byte)0x80,(byte)0xCA,0,0,0};
    interface Channel {byte[] exchange(byte[] command)throws IOException;}
    private NfcProtocol(){}
    static byte[] select(){return SELECT.clone();}
    static byte[] readCommand(){return READ.clone();}
    static boolean isSelect(byte[] command){return Arrays.equals(command,SELECT);}
    static boolean isRead(byte[] command){return Arrays.equals(command,READ);}
    static byte[] exchangeCommand(String code){ContactCode.parse(code);byte[] value=code.getBytes(StandardCharsets.UTF_8);byte[] command=new byte[value.length+5];command[0]=(byte)0x80;command[1]=(byte)0xDA;command[4]=(byte)value.length;System.arraycopy(value,0,command,5,value.length);return command;}
    static boolean isExchange(byte[] command){return command!=null&&command.length>=5&&command[0]==(byte)0x80&&command[1]==(byte)0xDA;}
    static String peer(byte[] command){if(!isExchange(command)||command[2]!=0||command[3]!=0||command.length>165||(command[4]&255)!=command.length-5)throw new IllegalArgumentException("Invalid NFC contact");String value=new String(command,5,command.length-5,StandardCharsets.UTF_8);ContactCode.parse(value);return value;}
    static byte[] status(int value){return new byte[]{(byte)(value>>8),(byte)value};}
    private static byte[] identity(String value){ContactCode.parse(value);byte[] bytes=value.getBytes(StandardCharsets.UTF_8);return Arrays.copyOf(bytes,bytes.length+2);}
    static byte[] response(String value){byte[] bytes=identity(value);bytes[bytes.length-2]=(byte)0x90;return bytes;}
    static String read(Channel channel)throws IOException {
        if(!Arrays.equals(channel.exchange(select()),status(0x9000)))throw new IOException("Open the contact page on the other unlocked phone.");
        return decode(channel.exchange(readCommand()));
    }
    static String exchange(Channel channel,String own)throws IOException {
        if(!Arrays.equals(channel.exchange(select()),status(0x9000)))throw new IOException("Open the contact page on both phones.");byte[] response=channel.exchange(exchangeCommand(own));if(Arrays.equals(response,status(0x6D00)))return read(channel);return decode(response);
    }
    private static String decode(byte[] response)throws IOException {
        if(response==null||response.length<3||response.length>162||response[response.length-2]!=(byte)0x90||response[response.length-1]!=0)throw new IOException("The contact was not received. Keep the phones together and try again.");
        String value=new String(response,0,response.length-2,StandardCharsets.UTF_8);
        try{ContactCode.parse(value);}catch(IllegalArgumentException error){throw new IOException("This is not a Priperi contact.",error);}return value;
    }
}
