package com.priperi;
import org.junit.Test;import static org.junit.Assert.*;
public class MessageLinksTest {
 @Test public void onlyVisibleHttpLinksHaveCanonicalBrowserTargets(){assertEquals("https://example.com/a?q=1#item",MessageLinks.safeUrl("https://example.com/a?q=1#item"));assertEquals("https://www.example.com/test",MessageLinks.safeUrl("www.example.com/test"));assertEquals("http://example.com",MessageLinks.safeUrl("http://example.com"));}
 @Test public void customSchemesCredentialsFormatControlsAndOversizedUrisStayPlainText(){for(String value:new String[]{"javascript:alert(1)","intent://example.com","file:///private","content://example","data:text/html,test","https://user:pass@example.com","https://example.com/\u202Ehidden","https://example.com/\nheader","https://", "https://example.com/"+"x".repeat(2048)})assertNull(value,MessageLinks.safeUrl(value));}
 @Test public void balancedUrlParenthesesArePreservedAndSentencePunctuationIsRemoved(){String value="https://example.com/a_(b)";assertEquals(value.length(),MessageLinks.linkEnd(value,0,value.length()));String wrapped=value+").";assertEquals(value.length(),MessageLinks.linkEnd(wrapped,0,wrapped.length()));}
 @Test public void plainTextRegexDoesNotTreatEmailOrJavascriptAsALink(){assertFalse(MessageLinks.LINKS.matcher("javascript:alert(1) mail@example.com").find());assertTrue(MessageLinks.LINKS.matcher("Look at https://example.com.").find());}
}
