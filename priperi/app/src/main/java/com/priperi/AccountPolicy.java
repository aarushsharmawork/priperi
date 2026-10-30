package com.priperi;
import android.content.Context;import android.os.SystemClock;import org.json.JSONObject;
/** Only authenticated foreground use renews server inactivity. Push reception never calls this. */
final class AccountPolicy {
 private static long lastAttempt;private static String lastUser;
 private static final java.util.concurrent.ExecutorService NETWORK=java.util.concurrent.Executors.newSingleThreadExecutor();
 private static final java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
 static String label(int days){return days==1?"1 day":days==7?"7 days":days==365?"1 year":"Never";}
 static void foreground(Vault v)throws Exception{
  if(!claimForeground(v))return;
  try{JSONObject policy=Repository.call(v,"accountPresence",new JSONObject());if(policy.has("days"))v.accountPolicy(policy);}catch(Session.BackendException e){if(e.status==404)return;throw e;}
 }
 static boolean claimForeground(Vault v){
  String user=Session.uid();if(!PriperiApp.privateForeground||user==null||v.account()==null||v.transferIn()!=null||v.transferOut()!=null)return false;
  long now=SystemClock.elapsedRealtime();if(user.equals(lastUser)&&now-lastAttempt<300000&&!v.policyNeedsCheck())return false;
  lastUser=user;lastAttempt=now;return true;
 }
 static void resumed(Context context){
  if(!PriperiApp.privateForeground||Session.uid()==null||!queued.compareAndSet(false,true))return;
  java.lang.ref.WeakReference<Context> owner=new java.lang.ref.WeakReference<>(context);
  NETWORK.execute(()->{try{new SyncEngine((SyncEngine.Progress)null).presence();}catch(Exception ignored){
   new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{Context current=owner.get();if(current instanceof PrivateActivity&&Session.uid()==null&&!((PrivateActivity)current).isFinishing()&&!((PrivateActivity)current).isDestroyed())((PrivateActivity)current).requireSignIn();});
  }finally{queued.set(false);}});
 }
 static void forget(){lastUser=null;lastAttempt=0;}
 private AccountPolicy(){}
}
