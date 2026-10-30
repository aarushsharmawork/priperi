package com.priperi;

import java.util.regex.Pattern;

/** Public contact identity only: no username, session or device secret. */
final class ContactCode {
    private static final String PREFIX="priperi:contact:1:";
    private static final Pattern ID=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern KEY=Pattern.compile("[0-9a-f]{64}");
    final String uid,fingerprint;
    private ContactCode(String uid,String fingerprint){this.uid=uid;this.fingerprint=fingerprint;}
    static String encode(String uid,String fingerprint){
        if(uid==null||fingerprint==null||!ID.matcher(uid).matches()||!KEY.matcher(fingerprint).matches())throw new IllegalArgumentException("Invalid contact identity.");
        return PREFIX+uid+":"+fingerprint;
    }
    static ContactCode parse(String value){
        if(value==null||value.length()!=PREFIX.length()+101||!value.startsWith(PREFIX))throw new IllegalArgumentException("This is not a Priperi contact code.");
        String uid=value.substring(PREFIX.length(),PREFIX.length()+36),key=value.substring(PREFIX.length()+37);
        if(value.charAt(PREFIX.length()+36)!=':'||!encode(uid,key).equals(value))throw new IllegalArgumentException("This is not a Priperi contact code.");
        return new ContactCode(uid,key);
    }
    void check(String owner,String returnedUid,String returnedFingerprint){
        if(uid.equals(owner))throw new IllegalArgumentException("This is your own code. Scan your contact’s code instead.");
        if(!uid.equals(returnedUid)||!fingerprint.equals(returnedFingerprint))throw new SecurityException("This code does not match the account’s security key. Ask for a new code.");
    }
}
