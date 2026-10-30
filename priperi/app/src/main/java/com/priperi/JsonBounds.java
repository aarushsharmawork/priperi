package com.priperi;

import java.io.IOException;

/** Check nesting before invoking recursive JSON parsers on external data. */
final class JsonBounds {
    private JsonBounds() {}
    static void check(String text, int maximumDepth) throws IOException {
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;
            else if(c=='{'||c=='['){if(++depth>maximumDepth)throw new IOException("Response nesting limit exceeded");}
            else if(c=='}'||c==']'){if(--depth<0)throw new IOException("Invalid response structure");}
        }
        if(quoted||depth!=0)throw new IOException("Incomplete response structure");
    }
}
