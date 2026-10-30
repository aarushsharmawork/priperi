package com.priperi;
import org.junit.Test;
import static org.junit.Assert.*;
public class ValidationTest {
    @Test public void usernameRejectsAmbiguousAndUnsafeNames() {
        for (String value : new String[]{"alice", "demo_alice_01", "abc"}) assertTrue(Validation.username(value));
        for (String value : new String[]{"Aarush", "../alice", "1alice", "aa", "a/b", "аlice", "a".repeat(25)}) assertFalse(Validation.username(value));
        assertFalse(Validation.username(null));
    }
    @Test public void passwordPolicyPreservesLongPasswordsWithoutTruncation() {
        assertTrue(Validation.password("Correct-Horse-2026!"));
        assertTrue(Validation.password("Aa1!" + "x".repeat(68)));
        for (String value : new String[]{"Aa1!short", "lowercase2026!", "UPPERCASE2026!", "NoDigitsHere!!", "NoSymbols2026", "Aa1!" + "x".repeat(69)}) assertFalse(Validation.password(value));
        assertFalse(Validation.password("Aa1!" + "é".repeat(35)));
        assertFalse(Validation.password(null));
    }
    @Test public void messageBoundaryAndWhitespaceAreChecked() {
        assertFalse(Validation.message(" \n\t")); assertFalse(Validation.message(null));
        assertTrue(Validation.message("hello\nworld")); assertTrue(Validation.message("x".repeat(4000))); assertFalse(Validation.message("x".repeat(4001)));
    }
}
