package com.priperi;

/** Process-memory consent only; never restored from storage, an intent or an NFC command. */
final class NfcShareLease {
    static final long LIFETIME_MS=90_000;
    private Object token;private String owner,code,peer;private long start,until;private java.util.function.Consumer<String> received;
    synchronized Object open(String uid,String payload,long now){
        return open(uid,payload,now,null);
    }
    synchronized Object open(String uid,String payload,long now,java.util.function.Consumer<String> callback){
        if(uid==null||!uid.equals(ContactCode.parse(payload).uid)||now<0||now>Long.MAX_VALUE-LIFETIME_MS)throw new IllegalArgumentException("Invalid contact sharing request");
        token=new Object();owner=uid;code=payload;peer=null;received=callback;start=now;until=now+LIFETIME_MS;return token;
    }
    synchronized void close(Object expected){if(token==expected){token=null;owner=null;code=null;peer=null;received=null;}}
    synchronized boolean accept(Object selected,String uid,long now,boolean unlocked,String value){if(selected==null||selected!=token||!valid(uid,now,unlocked)||received==null)return false;ContactCode contact=ContactCode.parse(value);if(contact.uid.equals(owner))return false;if(peer!=null)return peer.equals(value);peer=value;received.accept(value);return true;}
    synchronized Object current(String uid,long now,boolean unlocked){return valid(uid,now,unlocked)?token:null;}
    synchronized String read(Object selected,String uid,long now,boolean unlocked){return selected!=null&&selected==token&&valid(uid,now,unlocked)?code:null;}
    private boolean valid(String uid,long now,boolean unlocked){return token!=null&&unlocked&&owner.equals(uid)&&now>=start&&now<until;}
}
