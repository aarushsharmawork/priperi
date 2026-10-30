package com.priperi;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class NfcProtocolTest {
    private static final String UID="00000000-0000-4000-8000-000000000001",FP="a".repeat(64),CODE=ContactCode.encode(UID,FP);
    @Test public void onlyThePrivateContactAidAndExactReadCommandAreAccepted(){
        byte[] select=NfcProtocol.select();StringBuilder aid=new StringBuilder();for(int i=5;i<select.length-1;i++)aid.append(String.format(Locale.ROOT,"%02X",select[i]));assertEquals(NfcProtocol.AID,aid.toString());assertEquals(9,select[4]);assertTrue(NfcProtocol.isSelect(select));select[0]=1;assertFalse(NfcProtocol.isSelect(select));assertFalse(NfcProtocol.isSelect(null));assertFalse(NfcProtocol.isSelect(Arrays.copyOf(select,10000)));assertTrue(NfcProtocol.isRead(NfcProtocol.readCommand()));assertFalse(NfcProtocol.isRead(new byte[0]));
    }
    @Test public void twoRoundTripsCarryOnlyTheCanonicalPublicIdentity()throws Exception{
        int[] calls={0};String code=NfcProtocol.read(command->{calls[0]++;if(calls[0]==1){assertTrue(NfcProtocol.isSelect(command));return NfcProtocol.status(0x9000);}assertTrue(NfcProtocol.isRead(command));return NfcProtocol.response(CODE);});assertEquals(2,calls[0]);assertEquals(CODE,code);assertFalse(code.contains("password"));assertFalse(code.contains("username"));
    }
    @Test public void nonContactStatusOversizeMalformedAndTrailingPayloadsAreRejected(){
        assertThrows(IOException.class,()->NfcProtocol.read(c->NfcProtocol.status(0x6985)));
        for(byte[] response:new byte[][]{null,new byte[0],new byte[10000],NfcProtocol.status(0x9000),NfcProtocol.status(0x6D00)}){
            int[] count={0};assertThrows(IOException.class,()->NfcProtocol.read(c->++count[0]==1?NfcProtocol.status(0x9000):response));
        }
        byte[] extra=Arrays.copyOf(NfcProtocol.response(CODE),NfcProtocol.response(CODE).length+1);extra[extra.length-2]=(byte)0x90;int[] count={0};assertThrows(IOException.class,()->NfcProtocol.read(c->++count[0]==1?NfcProtocol.status(0x9000):extra));
    }
    @Test public void urlsUnexpectedVersionsAndBrokenContactKeysCannotBecomeContacts(){
        for(String value:new String[]{"https://invalid.example",CODE+"\n",CODE.replace(":1:",":2:"),CODE.toUpperCase(Locale.ROOT)})assertThrows(IllegalArgumentException.class,()->NfcProtocol.response(value));
        assertThrows(IOException.class,()->NfcProtocol.read(c->{throw new IOException("Tag lost");}));
    }
    @Test public void oneExchangeDeliversBothPublicCodesOnceAndRevokesOnClose()throws Exception{
        String other=ContactCode.encode("00000000-0000-4000-8000-000000000002","b".repeat(64));NfcShareLease lease=new NfcShareLease();List<String> delivered=new ArrayList<>();Object token=lease.open(UID,CODE,100,delivered::add);Object[] selected={null};
        NfcProtocol.Channel channel=command->{if(NfcProtocol.isSelect(command)){selected[0]=lease.current(UID,101,true);return NfcProtocol.status(selected[0]==null?0x6985:0x9000);}String own=lease.read(selected[0],UID,102,true);if(own==null||!lease.accept(selected[0],UID,102,true,NfcProtocol.peer(command)))return NfcProtocol.status(0x6985);return NfcProtocol.response(own);};
        assertEquals(CODE,NfcProtocol.exchange(channel,other));assertEquals(List.of(other),delivered);assertEquals(CODE,NfcProtocol.exchange(channel,other));assertEquals(1,delivered.size());
        assertFalse(lease.accept(token,UID,103,true,ContactCode.encode(UUID.randomUUID().toString(),"c".repeat(64))));lease.close(token);assertThrows(IOException.class,()->NfcProtocol.exchange(channel,other));assertEquals(1,delivered.size());
    }
    @Test public void mutualExchangeRejectsMalformedCommandsSelfLockedExpiredAndWrongOwner(){
        byte[] packet=NfcProtocol.exchangeCommand(CODE);assertEquals(CODE,NfcProtocol.peer(packet));packet[4]++;assertThrows(IllegalArgumentException.class,()->NfcProtocol.peer(packet));assertThrows(IllegalArgumentException.class,()->NfcProtocol.peer(new byte[10000]));
        String other=ContactCode.encode(UUID.randomUUID().toString(),"b".repeat(64));NfcShareLease lease=new NfcShareLease();List<String> delivered=new ArrayList<>();Object token=lease.open(UID,CODE,100,delivered::add);assertFalse(lease.accept(token,UID,101,true,CODE));assertFalse(lease.accept(token,UID,101,false,other));assertFalse(lease.accept(token,"different",101,true,other));assertFalse(lease.accept(token,UID,100+NfcShareLease.LIFETIME_MS,true,other));assertTrue(delivered.isEmpty());
    }
    @Test public void oldShareOnlyPhoneStillHasAReadOnlyFallback()throws Exception{
        int[] calls={0};assertEquals(CODE,NfcProtocol.exchange(command->{calls[0]++;if(NfcProtocol.isSelect(command))return NfcProtocol.status(0x9000);if(NfcProtocol.isExchange(command))return NfcProtocol.status(0x6D00);assertTrue(NfcProtocol.isRead(command));return NfcProtocol.response(CODE);},CODE));assertEquals(4,calls[0]);
    }
}
