package com.priperi;
import org.junit.Test;import java.io.IOException;import static org.junit.Assert.*;
public class JsonBoundsTest {
 @Test public void stringsDoNotCountAsContainers()throws Exception{JsonBounds.check("{\"text\":\"[[[\\\"{{{\",\"data\":[{}]}",3);}
 @Test public void depthLimitIsCheckedBeforeRecursiveParsing(){assertThrows(IOException.class,()->JsonBounds.check("[".repeat(12000)+"0"+"]".repeat(12000),32));}
 @Test public void truncatedAndUnbalancedStructuresAreRejected(){for(String s:new String[]{"{","}","{\"unterminated","[[]"})assertThrows(IOException.class,()->JsonBounds.check(s,32));}
 @Test public void exactBoundaryStillWorks()throws Exception{JsonBounds.check("[".repeat(32)+"0"+"]".repeat(32),32);assertThrows(IOException.class,()->JsonBounds.check("[".repeat(33)+"0"+"]".repeat(33),32));}
}
