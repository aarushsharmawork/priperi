package com.priperi;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Owns only unique local test vaults; never logs in or touches an owner's account. */
@RunWith(AndroidJUnit4.class)
public class StorageFlowTest {
    @Test public void legacyAndCompressedHistoryReopenMergeAndRemainAuthenticated()throws Exception {
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);Context context=ApplicationProvider.getApplicationContext();String scope="storage_"+UUID.randomUUID(),uid=UUID.randomUUID().toString(),peerId=UUID.randomUUID().toString();Vault vault=new Vault(context,scope),peer=new Vault(context,scope+"_peer");File backup=new File(context.getCacheDir(),"storage-"+UUID.randomUUID()+".enc");
        try {
            JSONObject me=profile(uid,"storage_owner",vault),other=profile(peerId,"storage_peer",peer);vault.account(me);TestIdentityTrust.pin(vault,other);
            StringBuilder text=new StringBuilder();for(int i=0;i<80;i++)text.append("Private Unicode message 世界 नमस्ते. ");String body=text.toString();
            JSONObject legacy=record(peer.crypto().encrypt(peerId,peer.identity(),me,body),peerId,body),modern=record(peer.crypto().encrypt(peerId,peer.identity(),me,body+"new"),peerId,body+"new");String oldId=legacy.getJSONObject("envelope").getString("id"),newId=modern.getJSONObject("envelope").getString("id");
            byte[] raw=legacy.toString().getBytes(Crypto.UTF8);ContentValues values=new ContentValues();values.put("id",oldId);values.put("payload",vault.crypto().seal(raw,"message:"+oldId));assertTrue(database(vault).insertOrThrow("messages",null,values)>0); // Existing pre-compression format.
            vault.store(modern);byte[] encrypted=blob(vault,newId),packed=vault.crypto().open(encrypted,"message:"+newId);assertEquals('P',packed[0]);assertEquals('R',packed[1]);assertEquals('Z',packed[2]);assertTrue(encrypted.length<modern.toString().getBytes(Crypto.UTF8).length);assertFalse(new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains("Private Unicode"));assertTrue(vault.seen(modern.getJSONObject("envelope")));
            assertEquals(body,vault.message(oldId).getString("body"));database(vault).setVersion(0);vault.close();vault=new Vault(context,scope);assertEquals(2,vault.messages(peerId).size());assertEquals(body+"new",vault.message(newId).getString("body"));
            legacy.put("state","archived");vault.store(legacy);assertEquals('P',vault.crypto().open(blob(vault,oldId),"message:"+oldId)[0]);
            byte[] key=new byte[32],salt=new byte[16];new java.security.SecureRandom().nextBytes(key);new java.security.SecureRandom().nextBytes(salt);assertEquals(2,vault.exportMessages(backup,key,salt,()->{}));vault.clearHistory(peerId);assertEquals(2,vault.restoreMessages(backup,key,()->{}));assertEquals(0,vault.restoreMessages(backup,key,()->{}));Arrays.fill(key,(byte)0);
            byte[] tampered=blob(vault,newId);tampered[tampered.length-1]^=1;ContentValues bad=new ContentValues();bad.put("payload",tampered);assertEquals(1,database(vault).update("messages",bad,"id=?",new String[]{newId}));boolean rejected=false;try{vault.message(newId);}catch(java.security.GeneralSecurityException expected){rejected=true;}assertTrue("Corrupted ciphertext reached decompression",rejected);
            System.out.println("ANDROID_RECORD raw="+modern.toString().getBytes(Crypto.UTF8).length+" encrypted="+encrypted.length);
        }finally{backup.delete();vault.destroy();peer.destroy();}
    }
    @Test public void compactionReclaimsSpaceWithoutChangingKeysOrSavedRows()throws Exception {
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);Context context=ApplicationProvider.getApplicationContext();String scope="compact_"+UUID.randomUUID();Vault v=new Vault(context,scope),peer=new Vault(context,scope+"_peer");
        try{String owner=UUID.randomUUID().toString(),sender=UUID.randomUUID().toString();JSONObject me=profile(owner,"compact_owner",v),other=profile(sender,"compact_peer",peer);v.account(me);TestIdentityTrust.pin(v,other);String identity=v.identity().toString();
            // Model an older database, which needs an explicit VACUUM to enable incremental reclamation.
            database(v).execSQL("PRAGMA auto_vacuum=NONE");database(v).execSQL("VACUUM");String keep=null;java.security.SecureRandom random=new java.security.SecureRandom();for(int i=0;i<50;i++){byte[] bytes=new byte[2000];random.nextBytes(bytes);String body=java.util.Base64.getEncoder().encodeToString(bytes);JSONObject env=peer.crypto().encrypt(sender,peer.identity(),me,body);v.store(record(env,sender,body));if(i==0)keep=env.getString("id");}
            long before=database(v).getPageSize()*android.database.DatabaseUtils.longForQuery(database(v),"PRAGMA page_count",null);JSONObject saved=v.message(keep);v.clearHistory(sender);v.store(saved);long reclaimed=v.maintainStorage(true);assertTrue("Automatic maintenance reclaimed no bytes",reclaimed>0);assertEquals(1,v.messages(sender).size());assertEquals(saved.getString("body"),v.message(keep).getString("body"));assertEquals(identity,v.identity().toString());v.close();v=new Vault(context,scope);assertEquals(1,v.messages(sender).size());assertTrue(v.seen(saved.getJSONObject("envelope")));System.out.println("COMPACTION before="+before+" reclaimed="+reclaimed);
        }finally{v.destroy();peer.destroy();}
    }
    private static JSONObject profile(String uid,String name,Vault v)throws Exception{return new JSONObject().put("uid",uid).put("username",name).put("hpke",v.identity().getString("hpke")).put("signing",v.identity().getString("signing")).put("fingerprint",v.identity().getString("fingerprint"));}
    private static JSONObject record(JSONObject envelope,String peer,String body)throws Exception{return new JSONObject().put("peer",peer).put("body",body).put("envelope",envelope).put("outgoing",false).put("state","received");}
    private static SQLiteDatabase database(Vault v)throws Exception{java.lang.reflect.Field field=Vault.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(v);}
    private static byte[] blob(Vault v,String id)throws Exception{try(Cursor c=database(v).rawQuery("SELECT payload FROM messages WHERE id=?",new String[]{id})){assertTrue(c.moveToFirst());return c.getBlob(0);}}
}
