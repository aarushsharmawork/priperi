package com.priperi;

import android.app.Activity;import android.net.Uri;import android.net.http.SslError;import android.view.*;import android.webkit.*;import android.widget.*;
import java.io.*;import java.net.HttpURLConnection;import java.net.URL;import java.nio.charset.StandardCharsets;import java.util.HashMap;import java.util.Map;import org.json.JSONObject;
import androidx.webkit.*;import java.net.URI;import java.util.Collections;import java.util.function.Consumer;

/** Only a provider token crosses this bridge; no password, identity key or app session enters the WebView. */
final class SignupCheck {
 static void show(Activity activity,String binding,Consumer<String> success,Consumer<String> failure){
  if(!(activity instanceof GuardedActivity)||!((GuardedActivity)activity).dialogAllowed()){failure.accept("Verification cancelled.");return;}
  if(!binding.matches("[a-f0-9]{64}")||!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){failure.accept("Update Android System WebView to complete account verification.");return;}
  final String endpoint,origin;try{endpoint=Session.endpoint();URI uri=URI.create(endpoint);origin=uri.getScheme()+"://"+uri.getAuthority();}catch(Exception error){failure.accept("Account verification is unavailable.");return;}
  LinearLayout box=Ui.column(activity);Ui.pad(activity,box,12);TextView status=Ui.text(activity,"Preparing verification…",14,R.color.muted);box.addView(status);
  final WebView web;try{web=new WebView(activity);}catch(RuntimeException error){failure.accept("Android System WebView is unavailable. Update it and try again.");return;}box.addView(web,new LinearLayout.LayoutParams(-1,Ui.dp(activity,Math.min(420,Math.max(220,activity.getResources().getConfiguration().screenHeightDp-220)))));
  web.setBackgroundColor(activity.getColor(R.color.page));WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setSaveFormData(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  boolean[] completed={false},closed={false};androidx.appcompat.app.AlertDialog dialog=Ui.dialog(activity).setTitle("Verify your signup").setView(box).setNegativeButton("Cancel",null).create();
  WebViewCompat.addWebMessageListener(web,"PriperiCaptcha",Collections.singleton(origin),(view,message,source,isMainFrame,reply)->{
   if(closed[0]||!isMainFrame||!sameOrigin(origin,source)||!((GuardedActivity)activity).dialogAllowed()||message.getType()!=WebMessageCompat.TYPE_STRING)return;
   String token=message.getData();if(token==null||token.length()<10||token.length()>2048)return;completed[0]=true;dialog.dismiss();success.accept(token);
  });
  final String pageUrl=endpoint+"/signup?binding="+binding;
  web.setWebViewClient(new WebViewClient(){
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
    if(!request.isForMainFrame()||!request.getUrl().toString().equals(pageUrl))return null;
    // Supabase serves an API response, not public HTML. Render only this fixed, TLS-checked endpoint.
    HttpURLConnection connection=null;try{
     connection=(HttpURLConnection)new URL(pageUrl).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setRequestProperty("Accept","application/json");
     if(connection.getResponseCode()!=200||!connection.getContentType().startsWith("application/json"))throw new IOException("Verification unavailable");
     ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[2048];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>20000)throw new IOException("Verification too large");bytes.write(buffer,0,n);}}
     String json=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();JsonBounds.check(json,4);String html=new JSONObject(json).getString("html");
     Map<String,String> headers=new HashMap<>();for(String key:new String[]{"Content-Security-Policy","Cache-Control","Referrer-Policy","X-Content-Type-Options"}){String value=connection.getHeaderField(key);if(value!=null)headers.put(key,value);}
     return new WebResourceResponse("text/html","UTF-8",200,"OK",headers,new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception error){return new WebResourceResponse("text/plain","UTF-8",503,"Unavailable",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream("Verification unavailable. Close and try again.".getBytes(StandardCharsets.UTF_8)));}finally{if(connection!=null)connection.disconnect();}
   }
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){if(!request.isForMainFrame())return false;return !request.getUrl().toString().equals(endpoint+"/signup?binding="+binding);}
   @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();status.setText("A secure verification connection could not be made.");}
   @Override public void onPageFinished(WebView view,String url){status.setText("Complete the check below.");}
   @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse error){if(request.isForMainFrame())status.setText("Verification is temporarily unavailable. Close this check and try again later.");}
   @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())status.setText("Verification could not load. Check your connection and try again.");}
  });
  dialog.setOnDismissListener(d->{closed[0]=true;web.stopLoading();WebViewCompat.removeWebMessageListener(web,"PriperiCaptcha");web.removeAllViews();web.destroy();if(!completed[0])failure.accept("Verification cancelled. Your account was not created.");});
  dialog.show();web.loadUrl(endpoint+"/signup?binding="+binding);
 }
 static boolean sameOrigin(String expected,Uri actual){try{URI e=URI.create(expected),a=URI.create(actual.toString());int ep=e.getPort()<0?("https".equals(e.getScheme())?443:80):e.getPort(),ap=a.getPort()<0?("https".equals(a.getScheme())?443:80):a.getPort();return e.getScheme().equalsIgnoreCase(a.getScheme())&&e.getHost().equalsIgnoreCase(a.getHost())&&ep==ap&&a.getRawUserInfo()==null&&a.getRawQuery()==null&&a.getRawFragment()==null&&(a.getPath()==null||a.getPath().isEmpty()||"/".equals(a.getPath()));}catch(Exception error){return false;}}
 private SignupCheck(){}
}
