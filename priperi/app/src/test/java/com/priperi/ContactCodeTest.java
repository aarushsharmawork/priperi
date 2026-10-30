package com.priperi;
import org.junit.Test;
import static org.junit.Assert.*;
public class ContactCodeTest {
    private static final String ID="00000000-0000-4000-8000-000000000001",OTHER="00000000-0000-4000-8000-000000000002",KEY="a".repeat(64);
    @Test public void canonicalPublicIdentityRoundTrips(){String payload=ContactCode.encode(ID,KEY);ContactCode code=ContactCode.parse(payload);assertEquals(ID,code.uid);assertEquals(KEY,code.fingerprint);code.check(OTHER,ID,KEY);assertFalse(payload.contains("username"));}
    @Test public void untrustedInputCannotOpenUrlsOrCarryExtraFields(){for(String value:new String[]{"https://evil.example",null,"",ContactCode.encode(ID,KEY)+"\n",ContactCode.encode(ID,KEY).toUpperCase(),"x".repeat(10000),ContactCode.encode(ID,KEY).replace(":1:",":2:")})assertThrows(IllegalArgumentException.class,()->ContactCode.parse(value));}
    @Test public void rejectsSelfSubstitutedIdentityOrChangedKey(){ContactCode code=ContactCode.parse(ContactCode.encode(ID,KEY));assertThrows(IllegalArgumentException.class,()->code.check(ID,ID,KEY));assertThrows(SecurityException.class,()->code.check(OTHER,OTHER,KEY));assertThrows(SecurityException.class,()->code.check(OTHER,ID,"b".repeat(64)));}
}
