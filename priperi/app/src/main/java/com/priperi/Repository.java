package com.priperi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class Repository {
    public interface Job<T> { T run(Vault vault) throws Exception; }
    public interface Result<T> { void done(T result); }
    public interface Failure { void fail(String message); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Vault current;
    private static final Runnable maintenance=()->IO.execute(()->{
        if(PriperiApp.foreground||current==null||current.transferOut()!=null||current.transferIn()!=null)return;
        try{DeviceSecurity.requireAllowed();android.os.BatteryManager power=PriperiApp.context().getSystemService(android.os.BatteryManager.class);current.maintainStorage(power!=null&&power.isCharging());}
        catch(Exception ignored){/* Reclaiming space is optional; never change message/account state on failure. */}
    });
    static void idle(){MAIN.removeCallbacks(maintenance);MAIN.postDelayed(maintenance,5000);}
    static void active(){MAIN.removeCallbacks(maintenance);}
    private Repository() {}
    public static <T> void local(Context context, Job<T> job, Result<T> success, Failure failure) {
        Context app=context.getApplicationContext();
        IO.execute(() -> {
            try {
                DeviceSecurity.requireAllowed();
                if(current==null){current=new Vault(app,"installation-v2");Session.restore(current);}
                T result=job.run(current);
                if(success!=null)MAIN.post(()->success.done(result));
            } catch(Exception e){if(e instanceof Session.BackendException&&"account-expired".equals(((Session.BackendException)e).reason)&&current!=null){try{SettingsActivity.removeLocalAccount(app,current);}catch(Exception cleanup){e.addSuppressed(cleanup);}}if(failure!=null){String message=friendly(e);MAIN.post(()->{
                if(context instanceof PrivateActivity&&Session.uid()==null)((PrivateActivity)context).requireSignIn();
                else failure.fail(message);
            });}}
        });
    }
    public static <T> void work(Context context, Job<T> job, Result<T> success, Failure failure) {
        local(context,v->{
            if(Session.uid()==null||v.session()==null||v.account()==null||!Session.uid().equals(v.account().optString("uid")))throw new SecurityException("Sign in to continue.");
            if(v.resetRequest()!=null)throw new SecurityException("Finish the account reset before opening chats.");
            return job.run(v);
        },success,failure);
    }
    static String uid() { return Session.uid(); }
    public static JSONObject call(Vault vault, String name, JSONObject payload) throws Exception {
        if(Session.uid()==null||!Session.uid().equals(vault.account().getString("uid")))throw new SecurityException("Sign in again.");
        if(PriperiApp.privateForeground&&!name.equals("accountPresence")&&!name.equals("getAccountPolicy")&&!name.equals("setSelfDestruct"))AccountPolicy.resumed(PriperiApp.context());
        payload.put("deviceSecret",vault.identity().getString("deviceSecret")).put("operation",name);
        try { return Session.request(Session.endpoint(),payload,Session.access(vault),true).getJSONObject("data"); }
        catch (Session.BackendException error) {
            if("account-expired".equals(error.reason)){SettingsActivity.removeLocalAccount(PriperiApp.context(),vault);throw error;}
            if(error.status==401&&!"reauthentication-required".equals(error.reason)) { Session.forget(); vault.session(null); }
            throw error;
        }
    }
    public static JSONObject lookup(Vault v, String username) throws Exception { return call(v, "lookupUser", new JSONObject().put("username", username)); }
    public static org.json.JSONArray search(Vault v, String query) throws Exception { return call(v, "searchUsers", new JSONObject().put("query", query)).getJSONArray("users"); }
    static JSONObject resolveCode(Vault v,ContactCode code)throws Exception {
        JSONObject peer=call(v,"lookupUser",new JSONObject().put("uid",code.uid));
        code.check(Session.uid(),peer.getString("uid"),peer.getString("fingerprint"));
        if(!Crypto.fingerprint(peer.getString("hpke"),peer.getString("signing")).equals(code.fingerprint))throw new SecurityException("This code does not match the account’s security key.");
        JSONObject old=v.contact(code.uid);
        if(old!=null&&old.optBoolean("blocked"))throw new SecurityException("Unblock this contact before opening the chat.");
        if(old!=null&&!code.fingerprint.equals(old.getString("fingerprint")))throw new SecurityException("Your contact’s security key changed. Sending is paused.");
        return peer;
    }
    public static JSONObject peer(Vault v, String uid) throws Exception {
        JSONObject cached = v.contact(uid); if (cached != null) return cached;
        JSONObject peer = call(v, "lookupUser", new JSONObject().put("uid", uid)); v.pin(peer); return peer;
    }
    static int remaining(JSONObject peer,List<JSONObject> messages) {
        JSONObject state=peer.optJSONObject("contactState");if(state!=null&&state.optBoolean("approved"))return Integer.MAX_VALUE;int accepted=0,queued=0;
        for(JSONObject m:messages)if(m.optBoolean("outgoing")){String status=m.optString("state");if("pending".equals(status))queued++;else if(m.optBoolean("approvalTracked")&&("sent".equals(status)||"delivered".equals(status)))accepted++;}
        return ContactAllowance.remaining(state!=null&&state.optBoolean("approved"),state==null?0:state.optInt("sentCount"),accepted,queued);
    }
    static JSONObject approveContact(Vault v,String peerId)throws Exception {
        JSONObject state=call(v,"approveContact",new JSONObject().put("uid",peerId));v.contactState(state);return state;
    }
    public static JSONObject send(Vault v, String peerId, String text) throws Exception {
        if(Session.uid()==null||v.account()==null||!Session.uid().equals(v.account().optString("uid")))throw new SecurityException("Sign in again.");
        MessageContent.parse(text);
        JSONObject peer = v.contact(peerId);
        if (peer == null || peer.optBoolean("blocked")) throw new SecurityException("This conversation is unavailable.");
        JSONObject permission=peer.optJSONObject("contactState");
        if((permission==null||!permission.optBoolean("approved"))&&((permission!=null&&permission.optInt("sentCount")>=3)||remaining(peer,v.records(peerId))<=0))throw new SecurityException("Wait for this contact to approve you before sending more messages.");
        JSONObject envelope = v.crypto().encrypt(uid(), v.identity(), peer, text);
        JSONObject record = new JSONObject().put("peer", peerId).put("body", text).put("envelope", envelope).put("outgoing", true).put("state", "pending").put("approvalTracked",true);
        MessageActions.decode(record,text);v.store(record); // durable outbox before any network request
        return record;
    }
    public static String sync(Vault v)throws Exception{
        SyncEngine engine=new SyncEngine(v);String warning=engine.inbox();String outgoing=engine.outbox();return outgoing.isEmpty()?warning:outgoing;
    }
    static final class Lease {
        final Vault vault;final String uid,proof;final long generation;
        Lease(Vault v)throws Exception{vault=v;uid=Session.uid();proof=v.identity().getString("deviceSecret");generation=Session.generation();check();}
        void check()throws Exception{
            DeviceSecurity.requireAllowed();
            if(vault!=current||uid==null||!uid.equals(Session.uid())||generation!=Session.generation()||vault.session()==null||vault.account()==null||!uid.equals(vault.account().optString("uid"))||!proof.equals(vault.identity().getString("deviceSecret")))throw new SecurityException("Sign in again.");
            if(vault.resetRequest()!=null||vault.transferIn()!=null||vault.transferOut()!=null)throw new SecurityException("Finish the account move or reset before syncing chats.");
        }
    }
    static <T>T stage(Lease lease,Job<T> job)throws Exception{
        Future<T> pending=IO.submit(()->{
            // Reject a removed/replaced vault before opening or creating storage.
            if(lease!=null)lease.check();else DeviceSecurity.requireAllowed();
            if(current==null){current=new Vault(PriperiApp.context(),"installation-v2");Session.restore(current);}
            try{return job.run(current);}catch(Session.BackendException error){if("account-expired".equals(error.reason))SettingsActivity.removeLocalAccount(PriperiApp.context(),current);throw error;}
        });
        try{return pending.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw e;}
    }
    static JSONObject exchange(Lease lease,String operation,JSONObject payload)throws Exception{
        String token=stage(lease,Session::access),endpoint=Session.endpoint();
        JSONObject request=new JSONObject(payload.toString()).put("operation",operation).put("deviceSecret",lease.proof);
        try{
            JSONObject result=Session.request(endpoint,request,token,true).getJSONObject("data");
            stage(lease,v->true);return result;
        }catch(Session.BackendException error){
            stage(lease,v->{
                if("account-expired".equals(error.reason))throw error;
                // A late response from an older token must not sign out a newly refreshed session.
                if(error.status==401&&!"reauthentication-required".equals(error.reason)&&token.equals(v.session().optString("access_token"))){Session.forget();v.session(null);}
                return true;
            });throw error;
        }
    }
    public static void reset() { IO.execute(() -> { if (current != null) current.close(); current = null; }); }
    public static void eraseLocal(Vault v) throws Exception { try{v.destroy();}finally{Session.forget();AccountPolicy.forget();KeyDelegation.clearCache();current=null;} }
    public static String friendly(Exception error) {
        Throwable e = error; while ((e instanceof ExecutionException || e instanceof java.lang.reflect.InvocationTargetException) && e.getCause() != null) e = e.getCause();
        if(e instanceof Session.BackendException)return e.getMessage();
        if(e instanceof java.net.SocketTimeoutException)return "The server took too long to respond. Your saved messages are safe. Try again.";
        if(e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException)return "You’re offline. Check your connection and try again.";
        if(e instanceof android.database.sqlite.SQLiteFullException)return "Your phone is out of space. Free some storage and try again. Messages have not been acknowledged.";
        if (e instanceof java.security.GeneralSecurityException) return "Private storage could not be opened safely. No data was overwritten.";
        if (e instanceof SecurityException) return e.getMessage();
        return "Couldn’t finish. Check your connection or available phone storage, then try again.";
    }
}
