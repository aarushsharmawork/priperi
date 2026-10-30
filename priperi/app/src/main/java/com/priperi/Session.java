package com.priperi;

import org.json.JSONObject;
import java.net.*;
import java.io.*;

/** Managed Supabase Auth tokens are stored only inside the encrypted vault. */
public final class Session {
    private static volatile String currentUid;
    private static volatile long generation;
    static long generation(){return generation;}
    private Session() {}
    static void restore(Vault vault) { generation++;JSONObject session=vault.session(),account=vault.account();String uid=session==null?null:session.optString("uid",null);currentUid=uid!=null&&account!=null&&uid.equals(account.optString("uid"))?uid:null; }
    static boolean localSessionMatches(Vault vault){JSONObject session=vault.session(),account=vault.account();return currentUid!=null&&session!=null&&account!=null&&currentUid.equals(session.optString("uid"))&&currentUid.equals(account.optString("uid"));}
    public static String uid() { return currentUid; }
    static void forget() { generation++;currentUid=null; }
    private static JSONObject devicePayload(Vault vault, String operation) throws Exception {
        JSONObject identity = vault.identity();
        return new JSONObject().put("operation", operation).put("deviceSecret", identity.getString("deviceSecret"))
            .put("installation", identity.getString("installation")).put("deviceId", identity.getString("deviceId"))
            .put("hpke", identity.getString("hpke")).put("signing", identity.getString("signing")).put("delegations",identity.optJSONArray("delegations")==null?new org.json.JSONArray():identity.getJSONArray("delegations"));
    }
    static JSONObject deviceStatus(Vault vault) throws Exception {
        try{return request(endpoint(),devicePayload(vault,"deviceStatus"),null,true).getJSONObject("data");}
        catch(BackendException error){if(error.status==403&&"permission-denied".equals(error.reason))throw new BackendException(error.getMessage(),error.status,"device-key-missing");throw error;}
    }
    static JSONObject replaceAccount(Vault vault,String username,String previousPassword,String password)throws Exception {return replaceAccount(vault,username,previousPassword,password,null);}
    static JSONObject replaceAccount(Vault vault,String username,String previousPassword,String password,String signupToken)throws Exception {
        if(vault.account()!=null||vault.session()!=null||vault.transferIn()!=null||vault.transferOut()!=null||vault.resetRequest()!=null)throw new SecurityException("This installation already has account data. Use Recovery options first.");
        JSONObject result;
        try{JSONObject preparation=devicePayload(vault,"prepareReplacement").put("username",username).put("previousPassword",previousPassword).put("confirmLoss",true);if(signupToken!=null)preparation.put("signupToken",signupToken);result=request(endpoint(),preparation,null,true).getJSONObject("data");}
        catch(BackendException error){if(error.status==404)throw new SecurityException("Fresh account signup is not available on this server yet. Try again after the service update.");throw error;}
        if(!result.optBoolean("prepared"))throw new SecurityException("The new account was not prepared. Nothing was removed.");
        return continueAccount(vault,username,password,signupToken);
    }
    static void resetAccount(Vault vault) throws Exception {
        JSONObject pending = vault.resetRequest();
        if (pending == null) {
            JSONObject status = deviceStatus(vault);
            if (!status.getBoolean("registered") && vault.account() != null) return;
            JSONObject challenge = request(endpoint(), devicePayload(vault, "resetChallenge"), null, true).getJSONObject("data");
            pending = devicePayload(vault, "resetAccount").put("uid", challenge.getString("uid")).put("nonce", challenge.getString("nonce"));
            pending.put("signature", vault.crypto().signDeviceReset(challenge.getString("uid"), challenge.getString("nonce"), vault.identity()));
            vault.resetRequest(pending);
        }
        try {
            JSONObject result = request(endpoint(), pending, null, true).getJSONObject("data");
            if (!"deleted".equals(result.optString("status"))) throw new SecurityException("Reset was not confirmed. No local data was removed.");
        } catch (BackendException error) {
            if (error.status == 403) vault.resetRequest(null);
            throw error;
        }
    }
    public static JSONObject continueAccount(Vault vault,String username,String password) throws Exception {return continueAccount(vault,username,password,null);}
    static JSONObject continueAccount(Vault vault,String username,String password,String signupToken)throws Exception {
        JSONObject identity=vault.identity();
        JSONObject payload=devicePayload(vault,"continue").put("password",password);
        if(username!=null&&!username.isEmpty())payload.put("username",username);if(signupToken!=null)payload.put("signupToken",signupToken);
        if(vault.account()!=null)payload.put("expectedUid",vault.account().getString("uid"));
        JSONObject response=request(endpoint(),payload,null,true).getJSONObject("data"), profile=response.getJSONObject("account");
        JSONObject old=vault.account();
        if(old!=null&&!old.getString("uid").equals(profile.getString("uid")))throw new SecurityException("This installation is linked to another account.");
        if(!identity.getString("fingerprint").equals(profile.getString("fingerprint")))throw new SecurityException("Account security key mismatch.");
        vault.account(profile);vault.session(response.getJSONObject("session"));generation++;currentUid=profile.getString("uid");return profile;
    }
    static String signupBinding(Vault vault,String name)throws Exception {JSONObject i=vault.identity();return TransferCipher.hash(String.join("\n","priperi-signup-v1",TransferCipher.hash(i.getString("deviceSecret").getBytes(Crypto.UTF8)),i.getString("installation"),i.optString("deviceId",""),name,i.getString("fingerprint")).getBytes(Crypto.UTF8));}
    public static String access(Vault vault) throws Exception {
        JSONObject session=vault.session();
        if(!localSessionMatches(vault))throw new SecurityException("Sign in to continue.");
        if(session.optLong("expires_at")*1000L<=System.currentTimeMillis()+60000) {
            JSONObject refreshed;
            try{refreshed=request(authUrl("token?grant_type=refresh_token"),new JSONObject().put("refresh_token",session.getString("refresh_token")),null,false);}
            catch(BackendException error){
                // Auth errors use top-level error_code, unlike relay errors. A
                // dead refresh token needs password sign-in, never local erasure.
                if((error.status==400||error.status==401||error.status==403)&&expiredAuth(error.reason)){
                    forget();vault.session(null);
                }
                throw error;
            }
            if(!currentUid.equals(refreshed.getJSONObject("user").getString("id")))throw new SecurityException("Sign in again.");
            refreshed.remove("user");if(!refreshed.has("expires_at"))refreshed.put("expires_at",System.currentTimeMillis()/1000L+refreshed.getLong("expires_in"));refreshed.put("uid",currentUid);vault.session(refreshed);session=refreshed;
        }
        return session.getString("access_token");
    }
    static void signOut(Vault vault) throws Exception {
        try { request(authUrl("logout?scope=local"),new JSONObject(),access(vault),false); }
        catch(BackendException error) { if(error.status!=401&&error.status!=403)throw error; } // already revoked
        finally { vault.session(null);forget(); }
    }
    static String endpoint() throws Exception {
        String value=BuildConfig.BACKEND_URL;
        if(value.isEmpty())throw new SecurityException("The server is not connected yet.");
        URL url=new URL(value);
        if(!url.getProtocol().equals("https")&&!(BuildConfig.DEBUG&&url.getProtocol().equals("http")&&url.getHost().equals("10.0.2.2")))throw new SecurityException("A secure server connection is required.");
        return value;
    }
    private static String authUrl(String path) throws Exception { URL url=new URL(endpoint());return url.getProtocol()+"://"+url.getAuthority()+"/auth/v1/"+path; }
    static JSONObject request(String address,JSONObject payload,String token,boolean relay) throws Exception {
        DeviceSecurity.requireAllowed();
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(25000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Cache-Control","no-store");
            if(!BuildConfig.SUPABASE_KEY.isEmpty())connection.setRequestProperty("apikey",BuildConfig.SUPABASE_KEY);
            if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
            byte[] bytes=payload.toString().getBytes(Crypto.UTF8);connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream stream=connection.getOutputStream()){stream.write(bytes);}
            int status=connection.getResponseCode();if(status==204)return new JSONObject();
            InputStream source=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            if(source==null)throw new IOException("Empty server response");
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream stream=source){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1){if(out.size()+n>8000000)throw new IOException("Response too large");out.write(buffer,0,n);}}
            JSONObject result=response(out.toByteArray());
            if(status<200||status>=300){JSONObject error=result.optJSONObject("error");String reason=relay?(error==null?"":error.optString("code")):result.optString("error_code",result.optString("code",""));String message=relay&&error!=null?error.optString("message"):status==401||!relay&&expiredAuth(reason)?"Your sign-in expired. Enter your password again.":"Couldn’t finish. Try again.";throw new BackendException(message,status,reason);}
            return result;
        } finally {connection.disconnect();}
    }
    static JSONObject response(byte[] bytes)throws Exception{
        if(bytes.length>8000000)throw new IOException("Response too large");
        String text=BackupCodec.utf8(bytes);JsonBounds.check(text,32);return new JSONObject(text);
    }
    private static boolean expiredAuth(String reason){return java.util.Arrays.asList("refresh_token_not_found","refresh_token_already_used","session_not_found","session_expired","user_not_found","user_banned","bad_jwt").contains(reason);}
    static final class BackendException extends IOException { final int status; final String reason; BackendException(String message,int status){this(message,status,"");} BackendException(String message,int status,String reason){super(message);this.status=status;this.reason=reason;} }
}
