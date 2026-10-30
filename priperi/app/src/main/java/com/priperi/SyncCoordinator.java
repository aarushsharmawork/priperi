package com.priperi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.*;
import java.util.LinkedHashSet;
import java.util.Set;

/** One foreground poller; push/send requests coalesce instead of stacking jobs. */
final class SyncCoordinator {
    interface Observer { void changed(String message); }
    interface Completion {void done(boolean retry);}
    private static final Set<Completion> background=new LinkedHashSet<>();
    static void requestBackground(Context context,Completion done){if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->requestBackground(context,done));return;}if(Session.uid()==null){done.done(false);return;}if(!connected()){done.done(true);return;}background.add(done);request(context);}
    static void cancelBackground(Completion done){background.remove(done);}
    private static void finishBackground(boolean retry){Set<Completion> pending=new LinkedHashSet<>(background);background.clear();for(Completion c:pending)c.done(retry);}
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Observer> observers = new LinkedHashSet<>();
    private static boolean running, requested,outboxRunning,outboxRequested;
    private static final java.util.concurrent.ExecutorService INBOX=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService OUTBOX=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final SyncBudget budget = new SyncBudget();
    private static final Set<Observer> conversations=new LinkedHashSet<>();
    private static final SyncPace pace=new SyncPace();
    private static String lastNotice="",displayedUser;
    private static long displayedRevision=-1;
    private static String completedUser;
    private static long completedRevision=-1;
    private static ConnectivityManager connectivity;
    private static boolean online;
    private static final ConnectivityManager.NetworkCallback network=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network n){networkChanged();}
        // A handover can deliver loss of the old network after the new one is
        // already active. Re-read the default network instead of stopping sync.
        @Override public void onLost(Network n){networkChanged();}
        @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){networkChanged();}
    };
    private static boolean connected(){try{ConnectivityManager manager=PriperiApp.context().getSystemService(ConnectivityManager.class);NetworkCapabilities c=manager.getNetworkCapabilities(manager.getActiveNetwork());return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(RuntimeException e){return false;}}
    private static void networkChanged(){MAIN.post(()->{if(observers.isEmpty())return;boolean next=connected();if(next&&!online){pace.reset();request(PriperiApp.context());}if(!next)MAIN.removeCallbacks(poll);online=next;});}
    private static final Runnable poll = () -> request(PriperiApp.context());
    private static final Runnable dispatch = SyncCoordinator::dispatch;
    static void attach(Observer observer){attach(observer,false);}
    static void attach(Observer observer,boolean conversation){
        observers.add(observer);if(conversation)conversations.add(observer);
        if(connectivity==null){try{connectivity=PriperiApp.context().getSystemService(ConnectivityManager.class);online=connected();connectivity.registerDefaultNetworkCallback(network);}catch(RuntimeException e){connectivity=null;}}
        pace.reset();request(PriperiApp.context());
    }
    static void detach(Observer observer) { observers.remove(observer);conversations.remove(observer);if(observers.isEmpty()){MAIN.removeCallbacks(poll);if(connectivity!=null){try{connectivity.unregisterNetworkCallback(network);}catch(RuntimeException ignored){}connectivity=null;}} }
    static void request(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> request(context)); return; }
        if (Session.uid() == null) return;
        requested = true; MAIN.removeCallbacks(poll);
        if(!connected()){online=false;return;}
        requestOutbox();
        if (!running) {
            MAIN.removeCallbacks(dispatch);
            MAIN.post(dispatch);
        }
    }
    private static void dispatch() {
        String user = Session.uid();
        if (!requested || running || user == null || !connected()) return;
        long now = SystemClock.elapsedRealtime(), wait = budget.delay(now);
        if (wait > 0) { MAIN.postDelayed(dispatch, wait); return; }
        requested = false; running = true; budget.record(now);
        // Push/send can run immediately while the rolling budget leaves room
        // below the existing 30/minute server quota for account checks.
        INBOX.execute(()->{
            try{SyncEngine engine=new SyncEngine(SyncCoordinator::progress);String notice=engine.inbox();long revision=engine.revision();MAIN.post(()->complete(user,notice,false,revision));}
            catch(Exception error){String message=Repository.friendly(error);MAIN.post(()->complete(user,message,true,-1));}
        });
    }
    static void retryApprovedOutbox(String user){if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->retryApprovedOutbox(user));return;}if(user.equals(Session.uid())&&connected())requestOutbox();}
    private static void requestOutbox(){
        outboxRequested=true;if(outboxRunning)return;outboxRequested=false;outboxRunning=true;
        String user=Session.uid();
        OUTBOX.execute(()->{
            String notice="";long revision=-1;boolean failed=false;
            try{SyncEngine engine=new SyncEngine(SyncCoordinator::progress);notice=engine.outbox();revision=engine.revision();}
            catch(Exception error){notice=Repository.friendly(error);failed=true;}
            final String message=notice;final long saved=revision;final boolean error=failed;
            MAIN.post(()->{
                outboxRunning=false;
                if(user!=null&&user.equals(Session.uid())){
                    if(!error)progress(user,message,saved);
                    else for(Observer observer:new LinkedHashSet<>(observers))observer.changed(message);
                }
                if(outboxRequested&&Session.uid()!=null&&connected())requestOutbox();
            });
        });
    }
    private static void progress(String user,String message,long revision){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->progress(user,message,revision));return;}
        if(!user.equals(Session.uid()))return;
        if(!user.equals(displayedUser)||revision!=displayedRevision||!message.equals(lastNotice)){
            displayedUser=user;displayedRevision=revision;lastNotice=message;
            for(Observer observer:new LinkedHashSet<>(observers))observer.changed(message);
        }
    }
    private static void complete(String user, String message, boolean failed,long revision) {
        running = false;
        if (!user.equals(Session.uid())) {
            requested = false;pace.reset();finishBackground(false);
            if (Session.uid() == null) for (Observer observer : new LinkedHashSet<>(observers)) observer.changed("Sign in again on your registered phone.");
            else if (!observers.isEmpty()&&connected()) request(PriperiApp.context());
            return;
        }
        boolean changed=!failed&&(!user.equals(completedUser)||revision!=completedRevision);
        if(!failed){completedUser=user;completedRevision=revision;displayedUser=user;displayedRevision=revision;}
        long delay=pace.complete(changed,failed,!conversations.isEmpty());
        if(changed||failed||!message.equals(lastNotice))for(Observer observer:new LinkedHashSet<>(observers))observer.changed(message);
        lastNotice=message;
        if (requested) request(PriperiApp.context());
        else {
            finishBackground(failed&&Session.uid()!=null);
            if (!observers.isEmpty()&&connected()) {
            MAIN.removeCallbacks(poll);
            MAIN.postDelayed(poll, delay);
            }
        }
    }
    private SyncCoordinator() {}
}
