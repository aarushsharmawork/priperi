package com.priperi;

import android.content.*;
import android.database.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SyncIndexTest {
    private static SQLiteDatabase db(Vault v)throws Exception{java.lang.reflect.Field f=Vault.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(v);}
    @Test public void indexedWorkingSetAndChatsAvoidUnrelatedHistoryAndRebuildOnUpgrade()throws Exception{
        Context c=ApplicationProvider.getApplicationContext();String scope="index_"+UUID.randomUUID(),owner=UUID.randomUUID().toString(),peer=UUID.randomUUID().toString();Vault v=new Vault(c,scope),other=new Vault(c,scope+"_other");
        try{
            JSONObject profile=new JSONObject().put("uid",peer).put("hpke",other.identity().getString("hpke")).put("signing",other.identity().getString("signing")).put("fingerprint",other.identity().getString("fingerprint"));
            String id=null,body="Private index benchmark "+"same text ".repeat(100);db(v).beginTransaction();try{
                for(int i=0;i<300;i++){
                    JSONObject env=v.crypto().encrypt(owner,v.identity(),profile,body);String conversation=i<3?peer:"group:"+UUID.nameUUIDFromBytes(("chat"+(i%6)).getBytes(Crypto.UTF8));
                    JSONObject m=new JSONObject().put("peer",conversation).put("body",body).put("envelope",env).put("outgoing",true).put("state",i<3?"pending":"sent");v.store(m);if(i==299)id=env.getString("id");
                }db(v).setTransactionSuccessful();
            }finally{db(v).endTransaction();}
            long start=SystemClock.elapsedRealtime();assertEquals(300,v.records(null).size());long full=SystemClock.elapsedRealtime()-start;
            start=SystemClock.elapsedRealtime();assertEquals(3,v.workRecords().size());long active=SystemClock.elapsedRealtime()-start;
            assertEquals(3,v.records(peer).size());
            android.os.Bundle metrics=new android.os.Bundle();metrics.putLong("history300Ms",full);metrics.putLong("workingSet3Ms",active);androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,metrics);
            // No plaintext conversation IDs or message IDs are added to derived indexes.
            try(Cursor rows=db(v).rawQuery("SELECT id,peer,payload FROM message_index",null)){while(rows.moveToNext()){assertTrue(rows.getString(0).matches("[a-f0-9]{64}"));assertTrue(rows.getString(1).matches("[a-f0-9]{64}"));assertFalse(new String(rows.getBlob(2),Crypto.UTF8).contains(peer));}}
            // A broken unrelated history row must not be read while syncing another chat.
            byte[] original;try(Cursor row=db(v).rawQuery("SELECT payload FROM messages WHERE id=?",new String[]{id})){assertTrue(row.moveToFirst());original=row.getBlob(0);}
            byte[] bad=original.clone();bad[bad.length-1]^=1;ContentValues patch=new ContentValues();patch.put("payload",bad);db(v).update("messages",patch,"id=?",new String[]{id});
            assertEquals(3,v.workRecords().size());assertEquals(3,v.records(peer).size());Vault current=v;assertThrows(java.security.GeneralSecurityException.class,()->current.records(null));
            patch.put("payload",original);db(v).update("messages",patch,"id=?",new String[]{id});
            // Simulate a pre-index database; reopening atomically backfills derived data.
            db(v).execSQL("DROP TABLE message_index");db(v).execSQL("DROP TABLE message_work");db(v).setVersion(0);v.close();v=new Vault(c,scope);assertEquals(3,v.workRecords().size());assertEquals(3,v.records(peer).size());
            JSONObject pending=v.workRecords().get(0);pending.put("state","delivered");v.store(pending);assertEquals(2,v.workRecords().size());v.clearHistory(peer);assertEquals(2,v.records(peer).size());assertEquals(2,v.workRecords().size());
            // Failure to save a derived index rolls back the authoritative row too.
            db(v).execSQL("CREATE TRIGGER fail_work BEFORE INSERT ON message_work BEGIN SELECT RAISE(ABORT,'test storage failure'); END");
            JSONObject attempt=v.workRecords().get(0);attempt.put("state","delivered");v.store(attempt);assertEquals(1,v.workRecords().size());attempt.put("state","pending");Vault target=v;assertThrows(Exception.class,()->target.store(attempt));assertEquals("delivered",v.message(attempt.getJSONObject("envelope").getString("id")).getString("state"));assertEquals(1,v.workRecords().size());
        }finally{v.destroy();other.destroy();}
    }
}
