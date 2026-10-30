package com.priperi;

import android.app.Activity;import android.content.*;import android.net.Uri;
import android.text.*;import android.text.method.LinkMovementMethod;import android.text.style.ClickableSpan;import android.view.View;import android.widget.TextView;
import java.net.URI;import java.util.*;import java.util.regex.*;import java.util.function.BooleanSupplier;

/** Plain visible HTTP links only. No HTML, previews, automatic requests or custom schemes. */
final class MessageLinks {
 static final Pattern LINKS=Pattern.compile("(?i)(?<![\\w@])(?:https?://|www\\.)[^\\s<>\\\"\\u0000-\\u001f\\u007f]{1,2048}");
 static String safeUrl(String text){
  if(text==null||text.length()>2048||text.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))return null;
  try{URI uri=new URI(text.regionMatches(true,0,"www.",0,4)?"https://"+text:text);String scheme=uri.getScheme();if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))||uri.getHost()==null||uri.getRawUserInfo()!=null)return null;return uri.toASCIIString();}catch(Exception e){return null;}
 }
 static int linkEnd(String body,int start,int end){
  while(end>start){char last=body.charAt(end-1);if(".,;!?".indexOf(last)>=0){end--;continue;}int close=")]}".indexOf(last);if(close<0)break;char open="([{ ".charAt(close);int balance=0;for(int i=start;i<end;i++){if(body.charAt(i)==open)balance++;if(body.charAt(i)==last)balance--;}if(balance<0)end--;else break;}return end;
 }
 static void bind(Activity activity,TextView view,String body,BooleanSupplier allowed){
  SpannableString text=new SpannableString(body);Matcher matches=LINKS.matcher(body);
  while(matches.find()){if(matches.end()<body.length()&&!Character.isWhitespace(body.charAt(matches.end()))&&"<>\"".indexOf(body.charAt(matches.end()))<0)continue;int end=matches.end();end=linkEnd(body,matches.start(),end);final String url=safeUrl(body.substring(matches.start(),end));if(url==null)continue;
   text.setSpan(new ClickableSpan(){public void onClick(View clicked){if(!allowed.getAsBoolean())return;String host=URI.create(url).getHost();Ui.dialog(activity).setTitle("Open link?").setMessage(host+"\n\n"+url).setNegativeButton("Cancel",null).setPositiveButton("Open",(d,w)->{if(!allowed.getAsBoolean())return;try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}catch(ActivityNotFoundException|SecurityException error){android.widget.Toast.makeText(activity,"No app can open this link.",android.widget.Toast.LENGTH_SHORT).show();}}).show();}public void updateDrawState(TextPaint paint){paint.setColor(activity.getColor(R.color.accent));paint.setUnderlineText(true);}},matches.start(),end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
  view.setText(text);view.setLinksClickable(true);view.setMovementMethod(LinkMovementMethod.getInstance());
 }
}
