package com.priperi;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;import org.junit.runner.RunWith;
import java.io.File;import java.security.MessageDigest;import java.util.concurrent.*;import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutomaticStorageTest {
    @Test public void upkeepWaitsForIdlePreservesIdentityAndNeverRecreatesRemovedStorage()throws Exception{
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);Context context=ApplicationProvider.getApplicationContext();boolean foreground=PriperiApp.foreground;Repository.reset();
        java.lang.reflect.Field work=Repository.class.getDeclaredField("maintenance"),executor=Repository.class.getDeclaredField("IO"),time=Vault.class.getDeclaredField("lastMaintenance");work.setAccessible(true);executor.setAccessible(true);time.setAccessible(true);Runnable maintenance=(Runnable)work.get(null);ExecutorService io=(ExecutorService)executor.get(null);
        CompletableFuture<Vault> opened=new CompletableFuture<>();Repository.local(context,v->{assertNull("Disposable installation required",v.account());v.setSetting("automaticStorageProbe",true);return v;},opened::complete,e->opened.completeExceptionally(new AssertionError(e)));Vault v=opened.get(30,TimeUnit.SECONDS);String identity=v.identity().toString();
        try{
            PriperiApp.foreground=true;maintenance.run();io.submit(()->{}).get(10,TimeUnit.SECONDS);assertEquals(0,time.getLong(v));
            PriperiApp.foreground=false;maintenance.run();io.submit(()->{}).get(10,TimeUnit.SECONDS);assertTrue(time.getLong(v)>0);assertEquals(identity,v.identity().toString());assertTrue(v.setting("automaticStorageProbe",false));
            io.submit(()->{try{Repository.eraseLocal(v);}catch(Exception e){throw new RuntimeException(e);}}).get(10,TimeUnit.SECONDS);maintenance.run();io.submit(()->{}).get(10,TimeUnit.SECONDS);
            String name="account_"+Crypto.hex(MessageDigest.getInstance("SHA-256").digest("installation-v2".getBytes(Crypto.UTF8)));assertFalse(new File(context.getNoBackupFilesDir(),name).exists());
        }finally{PriperiApp.foreground=foreground;Repository.reset();}
    }
}
