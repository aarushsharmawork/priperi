package com.priperi;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Lives only in noBackupFilesDir. Sensitive records and the keyset are AEAD encrypted. */
public final class Vault implements AutoCloseable {
    private final File directory;
    private final Crypto crypto;
    private final SQLiteDatabase db;
    private JSONObject meta;
    private long revision;private boolean destroyed;
    long revision(){return revision;}
    public Vault(Context context, String uid) throws Exception {
        directory = new File(context.getNoBackupFilesDir(), "account_" + Crypto.hex(MessageDigest.getInstance("SHA-256").digest(uid.getBytes(Crypto.UTF8))));
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Private storage unavailable");
        crypto = new Crypto(uid);
        File file = new File(directory, "identity.bin"); AtomicFile atomic = new AtomicFile(file);
        if (file.exists() || new File(file.getPath() + ".bak").exists()) {
            try (FileInputStream input = atomic.openRead()) { meta = new JSONObject(new String(crypto.open(readAll(input), "identity"), Crypto.UTF8)); }
        } else {
            // Never create a new identity over an existing message database.
            if (new File(directory, "history.db").exists()) throw new GeneralSecurityException("Missing identity");
            crypto.createKeys(); meta = new JSONObject().put("identity", crypto.newIdentity()).put("contacts", new JSONObject()).put("settings", new JSONObject()); saveMeta();
        }
        if (!identity().has("deviceId")) {
            identity().put("deviceId", DeviceIdentity.value(context, uid)); saveMeta();
        }
        boolean fresh=!new File(directory,"history.db").exists();
        db = SQLiteDatabase.openOrCreateDatabase(new File(directory, "history.db"), null);
        if(fresh)db.execSQL("PRAGMA auto_vacuum=INCREMENTAL");
        db.execSQL("PRAGMA synchronous=FULL");
        try (Cursor pragma = db.rawQuery("PRAGMA secure_delete=ON", null)) { pragma.moveToFirst(); }
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, payload BLOB NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS seen (id TEXT PRIMARY KEY, payload BLOB NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS message_changes (id TEXT PRIMARY KEY, payload BLOB NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS message_index (id TEXT PRIMARY KEY, peer TEXT NOT NULL, payload BLOB NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS message_peer ON message_index(peer)");
        db.execSQL("CREATE TABLE IF NOT EXISTS message_work (id TEXT PRIMARY KEY, payload BLOB NOT NULL)");
        if(db.getVersion()<1){
            db.beginTransaction();try{rebuildIndexes();db.setVersion(1);db.setTransactionSuccessful();}
            catch(Exception e){db.close();throw e;}finally{if(db.isOpen())db.endTransaction();}
        }
    }
    static byte[] readAll(InputStream stream) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; for (int n; (n = stream.read(buffer)) != -1;) out.write(buffer, 0, n); return out.toByteArray(); }
    private void saveMeta() throws Exception {
        if(destroyed)throw new IOException("This local account was removed. Open Priperi again.");
        AtomicFile atomic = new AtomicFile(new File(directory, "identity.bin")); FileOutputStream output = null;
        try { output = atomic.startWrite(); output.write(crypto.seal(meta.toString().getBytes(Crypto.UTF8), "identity")); atomic.finishWrite(output);revision++; }
        catch (Exception e) { if (output != null) atomic.failWrite(output); throw e; }
    }
    boolean muted(String peer){long until=meta.optJSONObject("settings").optLong("mute:"+peer,0);return until==-1||until>System.currentTimeMillis();}
    void mute(String peer,long until)throws Exception{if(peer==null||!peer.matches("(?:group:)?[a-f0-9-]{36}")||until< -1)throw new SecurityException("Invalid conversation");meta.getJSONObject("settings").put("mute:"+peer,until);saveMeta();}
    Set<String> pinnedChats() throws JSONException {
        Set<String> result=new HashSet<>();JSONObject settings=meta.getJSONObject("settings");
        for(Iterator<String> keys=settings.keys();keys.hasNext();){String key=keys.next();if(key.startsWith("chat-pin:")&&settings.optBoolean(key))result.add(key.substring(9));}
        return result;
    }
    void setChatPinned(String peer,boolean pinned) throws Exception {
        if(peer==null)throw new SecurityException("Invalid conversation");
        String id=peer.startsWith("group:")?peer.substring(6):peer;GroupRepository.checkId(id);
        if(peer.startsWith("group:")?group(id)==null:contact(id)==null)throw new SecurityException("Conversation is not saved");
        JSONObject settings=meta.getJSONObject("settings");String key="chat-pin:"+peer;Object previous=settings.opt(key);
        if(pinned)settings.put(key,true);else settings.remove(key);
        try{saveMeta();}catch(Exception error){if(previous==null)settings.remove(key);else settings.put(key,previous);throw error;}
    }
    public JSONObject identity() throws JSONException { return meta.getJSONObject("identity"); }
    public Crypto crypto() { return crypto; }
    public String pendingUsername() { return meta.optString("pendingUsername", ""); }
    public void pendingUsername(String name) throws Exception { meta.put("pendingUsername", name); saveMeta(); }
    public JSONObject account() { return meta.optJSONObject("account"); }
    public JSONObject session() { return meta.optJSONObject("session"); }
    public JSONObject resetRequest() { return meta.optJSONObject("resetRequest"); }
    public void resetRequest(JSONObject value) throws Exception { meta.put("resetRequest", value == null ? JSONObject.NULL : value); saveMeta(); }
    public void session(JSONObject value) throws Exception { meta.put("session", value == null ? JSONObject.NULL : value); saveMeta(); }
    public void account(JSONObject value) throws Exception { meta.put("account", value); saveMeta(); }
    public boolean setting(String key, boolean fallback) throws JSONException { return meta.getJSONObject("settings").optBoolean(key, fallback); }
    public boolean hasSetting(String key) throws JSONException { return meta.getJSONObject("settings").has(key); }
    public void setSetting(String key, boolean value) throws Exception { meta.getJSONObject("settings").put(key, value); saveMeta(); }
    public List<JSONObject> contacts() throws JSONException {
        List<JSONObject> result = new ArrayList<>(); JSONObject contacts = meta.getJSONObject("contacts");
        for (Iterator<String> it = contacts.keys(); it.hasNext();) result.add(contacts.getJSONObject(it.next()));
        result.sort(Comparator.comparing(o -> o.optString("username"))); return result;
    }
    public JSONObject contact(String uid) throws JSONException { return meta.getJSONObject("contacts").optJSONObject(uid); }
    public void pin(JSONObject peer) throws Exception {
        Crypto.verifyProfile(peer);String uid = peer.getString("uid");
        if (!Crypto.fingerprint(peer.getString("hpke"), peer.getString("signing")).equals(peer.getString("fingerprint"))) throw new GeneralSecurityException("Invalid security key");
        JSONObject old = contact(uid);
        if (old != null && !old.getString("fingerprint").equals(peer.getString("fingerprint"))) throw new GeneralSecurityException("Your contact’s security key changed. Sending is paused.");
        JSONObject clean=new JSONObject();for(String field:new String[]{"uid","username","hpke","signing","fingerprint","delegations","contactState"})if(peer.has(field))clean.put(field,peer.get(field));
        GroupRepository.checkId(uid);if(!Validation.username(clean.getString("username")))throw new GeneralSecurityException("Invalid contact name");
        if(old!=null&&!clean.has("contactState")&&old.has("contactState"))clean.put("contactState",old.getJSONObject("contactState"));
        clean.put("blocked",old!=null&&old.optBoolean("blocked")).put("verified",isVerified(clean)).put("readThrough",old==null?0:old.optLong("readThrough"));
        meta.getJSONObject("contacts").put(uid,clean);try{saveMeta();}catch(Exception error){meta.getJSONObject("contacts").put(uid,old==null?JSONObject.NULL:old);throw error;}
    }
    boolean isVerified(JSONObject peer){return peer!=null&&peer.optString("fingerprint").matches("[a-f0-9]{64}")&&peer.optString("fingerprint").equals(meta.optJSONObject("settings").optString("trusted-key:"+peer.optString("uid")));}
    void verifyIdentity(JSONObject peer)throws Exception{
        Crypto.verifyProfile(peer);GroupRepository.checkId(peer.getString("uid"));JSONObject old=contact(peer.getString("uid"));if(old!=null&&!old.getString("fingerprint").equals(peer.getString("fingerprint")))throw new GeneralSecurityException("Contact security key changed");
        saveTrust("trusted-key:"+peer.getString("uid"),peer.getString("fingerprint"));
    }
    private void saveTrust(String name,String value)throws Exception{JSONObject settings=meta.getJSONObject("settings");Object old=settings.opt(name);settings.put(name,value);try{saveMeta();}catch(Exception error){if(old==null)settings.remove(name);else settings.put(name,old);throw error;}}
    boolean groupVerified(JSONObject group)throws Exception{return GroupRepository.keysVerified(this,group)&&GroupRepository.roster(group).equals(meta.getJSONObject("settings").optString("trusted-group:"+group.getString("id")));}
    void verifyGroup(JSONObject group)throws Exception{JSONObject current=group(group.getString("id"));if(current==null||!current.optBoolean("active")||!GroupRepository.keysVerified(this,current)||!GroupRepository.roster(current).equals(GroupRepository.roster(group)))throw new SecurityException("Review the current members and their security keys first.");saveTrust("trusted-group:"+group.getString("id"),GroupRepository.roster(group));}

    public void contactState(JSONObject state)throws Exception {
        String uid=state.getString("uid");JSONObject contact=contact(uid);if(contact==null)return;
        int sent=state.getInt("sentCount");boolean approved=state.getBoolean("approved"),pending=state.getBoolean("incomingPending");if(sent<0||sent>3)throw new GeneralSecurityException("Invalid contact permission");
        Object name=state.opt("username");boolean renamed=false;
        if(name instanceof String){if(!Validation.username((String)name)||!contact.getString("fingerprint").equals(state.getString("fingerprint")))throw new GeneralSecurityException("Invalid contact update");renamed=!name.equals(contact.optString("username"));}
        JSONObject previous=contact.optJSONObject("contactState");if(!renamed&&previous!=null&&previous.optInt("sentCount")==sent&&previous.optBoolean("approved")==approved&&previous.optBoolean("incomingPending")==pending)return;
        JSONObject revised=new JSONObject(contact.toString());if(renamed)revised.put("username",name);revised.put("contactState",new JSONObject().put("uid",uid).put("sentCount",sent).put("approved",approved).put("incomingPending",pending));
        meta.getJSONObject("contacts").put(uid,revised);try{saveMeta();}catch(Exception e){meta.getJSONObject("contacts").put(uid,contact);throw e;}
    }
    JSONObject group(String id)throws JSONException {JSONObject groups=meta.optJSONObject("groups");return groups==null?null:groups.optJSONObject(id);}
    List<JSONObject> groups()throws JSONException {List<JSONObject> result=new ArrayList<>();JSONObject groups=meta.optJSONObject("groups");if(groups!=null)for(Iterator<String> it=groups.keys();it.hasNext();)result.add(groups.getJSONObject(it.next()));return result;}
    JSONArray groupInvites(){JSONArray invites=meta.optJSONArray("groupInvites");return invites==null?new JSONArray():invites;}
    void hideGroup(String id)throws Exception {JSONObject g=group(id);if(g!=null){g.put("hidden",true);saveMeta();}}
    void groupRead(String id,long through)throws Exception {JSONObject g=group(id);if(g!=null&&through>g.optLong("readThrough")){g.put("readThrough",through);saveMeta();}}
    void applyGroups(JSONObject data)throws Exception {
        JSONObject previous=meta.optJSONObject("groups"),next=previous==null?new JSONObject():new JSONObject(previous.toString());
        JSONArray active=data.optJSONArray("activeIds");Set<String> activeIds=new HashSet<>();if(active!=null){if(active.length()>20)throw new GeneralSecurityException("Invalid group list");for(int i=0;i<active.length();i++){String id=active.getString(i);GroupRepository.checkId(id);if(!activeIds.add(id))throw new GeneralSecurityException("Duplicate group");}}
        for(Iterator<String> it=next.keys();it.hasNext();){String id=it.next();next.getJSONObject(id).put("active",activeIds.contains(id));}
        JSONArray values=data.getJSONArray("groups"),invites=data.getJSONArray("invites");if(values.length()>20||invites.length()>50)throw new GeneralSecurityException("Invalid group list");
        for(int i=0;i<values.length();i++){
            JSONObject g=new JSONObject(values.getJSONObject(i).toString());String id=g.getString("id");GroupRepository.checkId(id);String name=g.getString("name");if(!GroupRepository.validName(name)||g.getInt("version")<1)throw new GeneralSecurityException("Invalid group");
            JSONObject old=group(id);JSONArray members=g.getJSONArray("members");if(members.length()<1||members.length()>20)throw new GeneralSecurityException("Invalid member list");boolean joined=false;Set<String> ids=new HashSet<>();
            for(int j=0;j<members.length();j++){JSONObject peer=members.getJSONObject(j);Crypto.verifyProfile(peer);String uid=peer.getString("uid");GroupRepository.checkId(uid);if(!ids.add(uid)||!Validation.username(peer.getString("username"))||!Crypto.fingerprint(peer.getString("hpke"),peer.getString("signing")).equals(peer.getString("fingerprint")))throw new GeneralSecurityException("Invalid member key");
                if(uid.equals(account().getString("uid"))){joined=true;if(!identity().getString("fingerprint").equals(peer.getString("fingerprint")))throw new GeneralSecurityException("Your group key changed");}
                JSONObject pinned=contact(uid),known=old==null?null:GroupRepository.member(old,uid);if((pinned!=null&&!pinned.getString("fingerprint").equals(peer.getString("fingerprint")))||(known!=null&&!known.getString("fingerprint").equals(peer.getString("fingerprint"))))throw new GeneralSecurityException("A group member’s security key changed");
            }
            if(!joined||(old!=null&&old.getInt("version")>g.getInt("version")))throw new GeneralSecurityException("Invalid group membership");
            g.put("active",true).put("readThrough",old==null?0:old.optLong("readThrough"));next.put(id,g);
        }
        for(String id:activeIds)if(next.optJSONObject(id)==null)throw new GeneralSecurityException("Group update missing");
        JSONArray oldInvites=meta.optJSONArray("groupInvites");
        // Validate every update above, but do not re-encrypt and fsync an unchanged cache.
        if(previous!=null&&oldInvites!=null&&previous.toString().equals(next.toString())&&oldInvites.toString().equals(invites.toString()))return;
        meta.put("groups",next).put("groupInvites",invites);
        try{saveMeta();}catch(Exception e){meta.put("groups",previous==null?JSONObject.NULL:previous).put("groupInvites",oldInvites==null?JSONObject.NULL:oldInvites);throw e;}
    }
    public void contactFlag(String uid, String key, boolean value) throws Exception { if("verified".equals(key)){if(!value)throw new SecurityException("Verification cannot be changed by a profile update");verifyIdentity(contact(uid));}contact(uid).put(key,value);saveMeta(); }
    public void markRead(String uid, long through) throws Exception { JSONObject peer = contact(uid); if (peer != null && through > peer.optLong("readThrough")) { peer.put("readThrough", through); saveMeta(); } }
    public JSONObject message(String id) throws Exception {
        try (Cursor cursor = db.rawQuery("SELECT payload FROM messages WHERE id=?", new String[]{id})) { return cursor.moveToFirst() ? decode(id, cursor.getBlob(0)) : null; }
    }
    private JSONObject decode(String id, byte[] blob) throws Exception {
        byte[] packed=crypto.open(blob,"message:"+id),plain=null;
        try{plain=RecordCodec.decode(packed);return new JSONObject(new String(plain,Crypto.UTF8));}
        finally{Arrays.fill(packed,(byte)0);if(plain!=null&&plain!=packed)Arrays.fill(plain,(byte)0);}
    }
    private String changeId(String key)throws Exception {javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(identity().optString("historySecret",identity().getString("deviceSecret")).getBytes(Crypto.UTF8),"HmacSHA256"));return Crypto.hex(mac.doFinal(("priperi-change-index-v1\n"+key).getBytes(Crypto.UTF8)));}
    private JSONObject change(String key)throws Exception {key=changeId(key);try(Cursor c=db.rawQuery("SELECT payload FROM message_changes WHERE id=?",new String[]{key})){return c.moveToFirst()?new JSONObject(new String(crypto.open(c.getBlob(0),"change:"+key),Crypto.UTF8)):null;}}
    private void change(String key,JSONObject value)throws Exception {key=changeId(key);ContentValues row=new ContentValues();row.put("id",key);row.put("payload",crypto.seal(value.toString().getBytes(Crypto.UTF8),"change:"+key));if(db.insertWithOnConflict("message_changes",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IOException("Message change could not be saved");}
    private String digest(JSONObject record)throws Exception {if(record.optBoolean("redacted")){String value=record.getString("_wireDigest");if(!value.matches("[a-f0-9]{64}"))throw new GeneralSecurityException("Invalid message digest");return value;}return envelopeDigest(record.getJSONObject("envelope"));}
    private void remember(JSONObject record,String digest)throws Exception {if(!record.optBoolean("outgoing")){String id=record.getJSONObject("envelope").getString("id");try(Cursor old=db.rawQuery("SELECT payload FROM seen WHERE id=?",new String[]{id})){if(old.moveToFirst()&&!digest.equals(new String(crypto.open(old.getBlob(0),"seen:"+id),Crypto.UTF8)))throw new GeneralSecurityException("Message identity conflict");}ContentValues row=new ContentValues();row.put("id",id);row.put("payload",crypto.seal(digest.getBytes(Crypto.UTF8),"seen:"+id));if(db.insertWithOnConflict("seen",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IOException("Receipt could not be saved");}}
    private static boolean activeRecord(JSONObject record){
        String state=record.optString("state");
        return record.optBoolean("outgoing")&&("pending".equals(state)||!record.optString("peer").startsWith("group:")&&"sent".equals(state));
    }
    private void indexRecord(JSONObject record)throws Exception{
        String id=record.getJSONObject("envelope").getString("id"),key=changeId("index:"+id);
        ContentValues row=new ContentValues();row.put("id",key);row.put("peer",changeId("peer:"+record.getString("peer")));
        row.put("payload",crypto.seal(id.getBytes(Crypto.UTF8),"index:"+key));
        if(db.insertWithOnConflict("message_index",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IOException("Message index could not be saved");
        if(activeRecord(record)){
            row.remove("peer");row.put("payload",crypto.seal(id.getBytes(Crypto.UTF8),"work:"+key));
            if(db.insertWithOnConflict("message_work",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IOException("Outbox index could not be saved");
        }else db.delete("message_work","id=?",new String[]{key});
    }
    private void rebuildIndexes()throws Exception{
        db.delete("message_index",null,null);db.delete("message_work",null,null);
        try(Cursor c=db.rawQuery("SELECT id,payload FROM messages",null)){while(c.moveToNext())indexRecord(decode(c.getString(0),c.getBlob(1)));}
    }
    private void deleteRecord(String id)throws Exception{
        db.delete("messages","id=?",new String[]{id});String key=changeId("index:"+id);
        db.delete("message_index","id=?",new String[]{key});db.delete("message_work","id=?",new String[]{key});
    }
    List<JSONObject> workRecords()throws Exception{return indexedRecords(null,true);}
    private List<JSONObject> indexedRecords(String peer,boolean work)throws Exception{
        List<JSONObject> result=new ArrayList<>();String table=work?"message_work":"message_index",purpose=work?"work:":"index:";
        try(Cursor c=db.rawQuery("SELECT id,payload FROM "+table+(work?"":" WHERE peer=?"),work?null:new String[]{changeId("peer:"+peer)})){
            while(c.moveToNext()){
                String key=c.getString(0),id=new String(crypto.open(c.getBlob(1),purpose+key),Crypto.UTF8);
                if(!key.equals(changeId("index:"+id)))throw new GeneralSecurityException("Invalid message index");
                JSONObject record=message(id);
                if(record==null||(work?!activeRecord(record):!peer.equals(record.optString("peer"))))throw new GeneralSecurityException("Invalid message index");
                result.add(record);
            }
        }
        result.sort(Comparator.comparingLong(o->o.optJSONObject("envelope").optLong("sentAt")));return result;
    }
    private void writeRecord(JSONObject record)throws Exception {
        String id=record.getJSONObject("envelope").getString("id"),wireDigest=digest(record);JSONObject prior=message(id);
        if(prior!=null&&!digest(prior).equals(wireDigest))throw new GeneralSecurityException("Message identity conflict");
        record.put("_wireDigest",wireDigest);
        if(!record.optBoolean("hidden")){
            JSONObject env=record.getJSONObject("envelope"),local=change("local:"+id),update=change("remote:"+id+":"+env.getString("sender"));
            if(update!=null)try{MessageActions.apply(record,update);}catch(MessageActions.InvalidChange invalid){db.delete("message_changes","id=?",new String[]{changeId("remote:"+id+":"+env.getString("sender"))});}
            if(local!=null){if(!wireDigest.equals(local.getString("digest")))throw new GeneralSecurityException("Message identity conflict");record.put("localDeleted",true).put("body","");record.remove("replyTo");}
        }
        boolean pending="pending".equals(record.optString("state"));
        if(!pending&&(record.optBoolean("deleted")||record.optBoolean("localDeleted")||record.optBoolean("edited")||record.optBoolean("hidden"))){JSONObject env=new JSONObject(record.getJSONObject("envelope").toString());env.put("ciphertext","").put("signature","");record.put("envelope",env).put("redacted",true);if(record.optBoolean("hidden")){record.put("body","");record.remove("change");}}
        ContentValues values=new ContentValues();values.put("id",id);byte[] plain=record.toString().getBytes(Crypto.UTF8),packed=null;
        try{packed=RecordCodec.encode(plain);values.put("payload",crypto.seal(packed,"message:"+id));}finally{Arrays.fill(plain,(byte)0);if(packed!=null&&packed!=plain)Arrays.fill(packed,(byte)0);}
        if(db.insertWithOnConflict("messages",null,values,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IOException("Message could not be saved");remember(record,wireDigest);indexRecord(record);
    }
    public void store(JSONObject record)throws Exception {
        db.beginTransaction();try {
            JSONObject env=record.getJSONObject("envelope"),same=message(env.getString("id"));if(same!=null&&!digest(same).equals(digest(record)))throw new GeneralSecurityException("Message identity conflict");
            if(record.optBoolean("hidden")){
                JSONObject c=record.optJSONObject("change");String target=c==null?record.getString("actionTarget"):c.getString("target"),key="remote:"+target+":"+env.getString("sender");
                JSONObject previous=change(key),candidate=record.optBoolean("redacted")?null:MessageActions.mutation(record),original=message(target);
                if(candidate!=null){
                    if(original!=null)MessageActions.apply(new JSONObject(original.toString()),candidate);
                    else if(previous==null){try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM message_changes",null)){count.moveToFirst();if(count.getLong(0)>=5000)throw new GeneralSecurityException("Too many unresolved message changes");}}
                    if(previous==null||previous.getInt("kind")!=3&&(candidate.getInt("kind")==3||candidate.getLong("at")>previous.getLong("at")||candidate.getLong("at")==previous.getLong("at")&&candidate.getString("event").compareTo(previous.getString("event"))>0))previous=candidate;
                }
                if(previous!=null){if(change("local:"+target)!=null)previous.put("text","");if(previous.getString("event").equals(env.getString("id")))previous.put("state",record.optString("state"));change(key,previous);}
                record.put("actionTarget",target);
                if(record.optBoolean("outgoing"))writeRecord(record);else remember(record,digest(record));
                if(original!=null)writeRecord(original);
                String state=record.optString("state");if(record.optBoolean("outgoing")&&("delivered".equals(state)||"expired".equals(state)||"group-changed".equals(state)||env.has("group")&&"sent".equals(state)))deleteRecord(env.getString("id"));
            }else writeRecord(record);
            db.setTransactionSuccessful();revision++;
        }finally{db.endTransaction();}
    }
    void discardChange(JSONObject record)throws Exception {
        if(!record.optBoolean("hidden")||record.optBoolean("outgoing"))throw new GeneralSecurityException("Invalid discard request");
        db.beginTransaction();try{String id=record.getJSONObject("envelope").getString("id"),value=digest(record);JSONObject same=message(id);if(same!=null&&!digest(same).equals(value))throw new GeneralSecurityException("Message identity conflict");remember(record,value);db.setTransactionSuccessful();revision++;}finally{db.endTransaction();}
    }
    void deleteMessage(String peer,String id)throws Exception {
        JSONObject record=message(id);if(record==null||!peer.equals(record.optString("peer"))||record.optBoolean("hidden"))throw new SecurityException("Message unavailable");
        db.beginTransaction();try{change("local:"+id,new JSONObject().put("digest",digest(record)));String key="remote:"+id+":"+record.getJSONObject("envelope").getString("sender");JSONObject update=change(key);if(update!=null){update.put("text","");change(key,update);}writeRecord(record);db.setTransactionSuccessful();revision++;}finally{db.endTransaction();}reclaimPages();
    }
    private long lastMaintenance;
    long maintainStorage(boolean convertSmallLegacy)throws Exception {
        if(destroyed||!db.isOpen()||db.inTransaction())return 0;
        long now=android.os.SystemClock.elapsedRealtime();if(lastMaintenance!=0&&now-lastMaintenance<60000)return 0;lastMaintenance=now;
        File history=new File(directory,"history.db");long before=history.length(),free;
        try(Cursor rows=db.rawQuery("PRAGMA freelist_count",null)){rows.moveToFirst();free=rows.getLong(0);}
        int mode;try(Cursor rows=db.rawQuery("PRAGMA auto_vacuum",null)){rows.moveToFirst();mode=rows.getInt(0);}
        // Older databases need one rewrite to enable incremental reclamation.
        // Do this only for small files during idle/charging maintenance. Larger
        // legacy files safely reuse freed pages instead of blocking chat opening.
        if(mode==0&&convertSmallLegacy&&free>=32&&before<=16L*1024*1024&&directory.getUsableSpace()>=before*2+4*1024*1024){
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL");db.execSQL("VACUUM");
        }else if(mode==2&&free>=32){
            try(Cursor rows=db.rawQuery("PRAGMA incremental_vacuum(64)",null)){while(rows.moveToNext()){}}
        }
        return Math.max(0,before-history.length());
    }
    private String envelopeDigest(JSONObject envelope) throws Exception { return Crypto.hex(MessageDigest.getInstance("SHA-256").digest((Crypto.context(envelope) + "\n" + envelope.getString("ciphertext") + "\n" + envelope.getString("signature")).getBytes(Crypto.UTF8))); }
    public boolean seen(JSONObject envelope) throws Exception {
        String id = envelope.getString("id");
        try (Cursor cursor = db.rawQuery("SELECT payload FROM seen WHERE id=?", new String[]{id})) {
            if (!cursor.moveToFirst()) return false;
            String digest = new String(crypto.open(cursor.getBlob(0), "seen:" + id), Crypto.UTF8);
            if (!digest.equals(envelopeDigest(envelope))) throw new GeneralSecurityException("Message identity conflict"); return true;
        }
    }
    public List<JSONObject> messages(String peer)throws Exception {List<JSONObject> result=records(peer);result.removeIf(m->m.optBoolean("hidden")||m.optBoolean("localDeleted"));return result;}
    List<JSONObject> records(String peer) throws Exception {
        if(peer!=null)return indexedRecords(peer,false);
        List<JSONObject> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id,payload FROM messages", null)) {
            while (cursor.moveToNext()) { JSONObject m = decode(cursor.getString(0), cursor.getBlob(1)); if (peer == null || peer.equals(m.optString("peer"))) result.add(m); }
        }
        result.sort(Comparator.comparingLong(o -> o.optJSONObject("envelope").optLong("sentAt"))); return result;
    }
    private void reclaimPages(){try(Cursor mode=db.rawQuery("PRAGMA auto_vacuum",null);Cursor free=db.rawQuery("PRAGMA freelist_count",null)){if(mode.moveToFirst()&&mode.getInt(0)==2&&free.moveToFirst()&&free.getInt(0)>=128)try(Cursor reclaimed=db.rawQuery("PRAGMA incremental_vacuum(64)",null)){while(reclaimed.moveToNext()){}}}catch(android.database.sqlite.SQLiteException ignored){/* Optional maintenance never changes whether deletion committed. */}}
    public void clearHistory(String peer)throws Exception {
        db.beginTransaction();try{for(JSONObject m:records(peer))if(!"pending".equals(m.optString("state"))){String id=m.getJSONObject("envelope").getString("id");deleteRecord(id);String key="remote:"+id+":"+m.getJSONObject("envelope").getString("sender");JSONObject update=change(key);if(update!=null&&update.optInt("kind")==2)db.delete("message_changes","id=?",new String[]{changeId(key)});}db.setTransactionSuccessful();revision++;}finally{db.endTransaction();}reclaimPages();
    }
    int exportMessages(File file,byte[] key,byte[] salt,BackupCodec.Check check)throws Exception {
        if(account()==null)throw new SecurityException("Sign in again.");
        try(OutputStream output=new FileOutputStream(file)) {
            return BackupCodec.write(output,key,salt,account().getString("uid"),identity().getString("fingerprint"),consumer->{
                try(Cursor cursor=db.rawQuery("SELECT id,payload FROM messages ORDER BY id",null)) {
                    while(cursor.moveToNext()){check.run();JSONObject m=decode(cursor.getString(0),cursor.getBlob(1));if(!m.optBoolean("hidden")&&!m.optBoolean("deleted")&&!m.optBoolean("localDeleted")&&!"pending".equals(m.optString("state"))&&!"group-changed".equals(m.optString("state"))){JSONObject clean=new JSONObject().put("peer",m.getString("peer")).put("body",m.getString("body")).put("envelope",m.getJSONObject("envelope")).put("outgoing",m.getBoolean("outgoing")).put("state",m.getString("state"));if(m.has("author"))clean.put("author",m.getJSONObject("author"));for(String field:new String[]{"replyTo","edited","redacted","_wireDigest","unsupported"})if(m.has(field))clean.put(field,m.get(field));byte[] bytes=clean.toString().getBytes(Crypto.UTF8);try{consumer.accept(bytes);}finally{java.util.Arrays.fill(bytes,(byte)0);}}}
                }
            },crypto::signBackup,check);
        }
    }
    int restoreMessages(File file,byte[] key,BackupCodec.Check check)throws Exception {
        if(account()==null||file.length()>BackupCodec.MAX_BYTES)throw new GeneralSecurityException("Invalid backup or account");
        String owner=account().getString("uid"),fingerprint=identity().getString("fingerprint");
        // Authenticate the complete stream AND device signature before parsing or changing history.
        try(InputStream input=new FileInputStream(file)){BackupCodec.read(input,key,owner,fingerprint,null,(value,signature)->Crypto.verifySignature(accountWithIdentity(),value,Crypto.b64(signature)),check);}
        int[] added={0};db.beginTransaction();try {
            try(InputStream input=new FileInputStream(file)) {
                BackupCodec.read(input,key,owner,fingerprint,bytes->{
                    String text=BackupCodec.utf8(bytes);int depth=0;boolean quoted=false,escape=false;
                    for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>4)throw new IOException("Invalid backup nesting");}else if(c=='}'||c==']')depth--;}
                    JSONObject m=new JSONObject(text),envelope=m.getJSONObject("envelope");String peer=m.getString("peer"),id=envelope.getString("id");
                    if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")||!Validation.message(m.getString("body"))||!(m.get("outgoing") instanceof Boolean))throw new IOException("Invalid backup message");
                    boolean outgoing=m.getBoolean("outgoing");String state=m.getString("state");if(!java.util.Arrays.asList("sent","delivered","expired","received","archived").contains(state))throw new IOException("Unsent messages cannot be restored");
                    if(envelope.has("group")){
                        String gid=envelope.getString("group");if(!peer.equals("group:"+gid)||group(gid)==null||!owner.equals(envelope.getString(outgoing?"sender":"recipient"))||!fingerprint.equals(envelope.getString(outgoing?"senderFingerprint":"recipientFingerprint")))throw new GeneralSecurityException("Backup group identity mismatch");
                        if(!outgoing){JSONObject author=m.getJSONObject("author");if(!author.getString("uid").equals(envelope.getString("sender"))||!Crypto.fingerprint(author.getString("hpke"),author.getString("signing")).equals(envelope.getString("senderFingerprint")))throw new GeneralSecurityException("Invalid backup author");}
                    }else{
                        JSONObject contact=contact(peer);if(contact==null)throw new GeneralSecurityException("This contact is not saved on this phone");
                        if(!owner.equals(envelope.getString(outgoing?"sender":"recipient"))||!peer.equals(envelope.getString(outgoing?"recipient":"sender"))
                           ||!fingerprint.equals(envelope.getString(outgoing?"senderFingerprint":"recipientFingerprint"))||!contact.getString("fingerprint").equals(envelope.getString(outgoing?"recipientFingerprint":"senderFingerprint")))throw new GeneralSecurityException("Backup identity mismatch");
                    }
                    long sentAt=envelope.getLong("sentAt");if(sentAt<0||sentAt>253402300799999L||envelope.getString("ciphertext").length()>32768||envelope.getString("signature").length()>512)throw new IOException("Invalid backup envelope");
                    JSONObject existing=message(id);if(existing!=null){if(!digest(existing).equals(digest(m)))throw new GeneralSecurityException("Message identity conflict");return;}
                    // Reconstruct a minimal history row: no permissions, outbox replay or caller-controlled flags.
                    JSONObject clean=new JSONObject().put("peer",peer).put("body",m.getString("body")).put("envelope",envelope).put("outgoing",outgoing).put("state",outgoing?"archived":"received");if(envelope.has("group")&&m.has("author"))clean.put("author",m.getJSONObject("author"));for(String field:new String[]{"replyTo","edited","redacted","_wireDigest","unsupported"})if(m.has(field))clean.put(field,m.get(field));store(clean);added[0]++;
                },(value,signature)->Crypto.verifySignature(accountWithIdentity(),value,Crypto.b64(signature)),check);
            }
            check.run();db.setTransactionSuccessful();return added[0];
        } finally {db.endTransaction();}
    }
    File transferFile(String lookup)throws Exception{if(!lookup.matches("[a-f0-9]{64}"))throw new GeneralSecurityException("Invalid transfer");return new File(directory,"move-"+lookup+".enc");}
    int exportTransfer(File file,byte[] key,TransferArchive.Check check)throws Exception{return exportTransfer(file,key,check,true);}
    int exportTransfer(File file,byte[] key,TransferArchive.Check check,boolean history)throws Exception{
        JSONObject metadata=new JSONObject();for(String name:new String[]{"contacts","groups","settings"})if(meta.has(name))metadata.put(name,meta.get(name));
        try(OutputStream output=new FileOutputStream(file)){return TransferArchive.write(output,key,TransferArchive.salt(),account().getString("uid"),identity().getString("fingerprint"),consumer->{
            consumer.accept(new JSONObject().put("table","meta").put("id","meta").put("data",metadata).toString().getBytes(Crypto.UTF8));
            for(String table:history?new String[]{"messages","seen","message_changes"}:new String[]{})try(Cursor rows=db.rawQuery("SELECT id,payload FROM "+table+" ORDER BY id",null)){while(rows.moveToNext()){check.run();String id=rows.getString(0),purpose=table.equals("messages")?"message:":table.equals("seen")?"seen:":"change:";byte[] plain=crypto.open(rows.getBlob(1),purpose+id);try{consumer.accept(new JSONObject().put("table",table).put("id",id).put("data",Crypto.b64(plain)).toString().getBytes(Crypto.UTF8));}finally{Arrays.fill(plain,(byte)0);}}}
        },crypto::signBackup,check);}
    }
    static JSONObject transferObject(byte[] bytes)throws Exception{
        String text=BackupCodec.utf8(bytes);int depth=0;boolean quote=false,escape=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quote){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quote=false;}else if(c=='"')quote=true;else if(c=='{'||c=='['){if(++depth>24)throw new IOException("Transfer nesting limit");}else if(c=='}'||c==']'){if(--depth<0)throw new IOException("Invalid transfer structure");}}
        if(quote||depth!=0)throw new IOException("Invalid transfer structure");return new JSONObject(text);
    }
    private static JSONObject transferRecord(byte[] bytes,String owner)throws Exception{
        JSONObject row=transferObject(bytes);if(row.length()!=3)throw new GeneralSecurityException("Invalid transfer record");String table=row.getString("table"),id=row.getString("id");
        if(table.equals("meta")){if(!id.equals("meta"))throw new GeneralSecurityException("Invalid transfer metadata");JSONObject metadata=row.getJSONObject("data");for(Iterator<String> names=metadata.keys();names.hasNext();)if(!Arrays.asList("contacts","groups","settings").contains(names.next()))throw new GeneralSecurityException("Invalid transfer metadata");JSONObject contacts=metadata.getJSONObject("contacts");for(Iterator<String> ids=contacts.keys();ids.hasNext();){String uid=ids.next();JSONObject peer=contacts.getJSONObject(uid);if(!uid.equals(peer.getString("uid"))||!Validation.username(peer.getString("username")))throw new GeneralSecurityException("Invalid transferred contact");Crypto.verifyProfile(peer);}JSONObject groups=metadata.optJSONObject("groups");if(groups!=null)for(Iterator<String> ids=groups.keys();ids.hasNext();){String gid=ids.next();JSONObject group=groups.getJSONObject(gid);GroupRepository.checkId(gid);if(!gid.equals(group.getString("id"))||!GroupRepository.validName(group.getString("name")))throw new GeneralSecurityException("Invalid transferred group");JSONArray members=group.getJSONArray("members");if(members.length()>20)throw new GeneralSecurityException("Invalid transferred group");for(int j=0;j<members.length();j++)Crypto.verifyProfile(members.getJSONObject(j));}return row;}
        if(!Arrays.asList("messages","seen","message_changes").contains(table)||!id.matches(table.equals("message_changes")?"[a-f0-9]{64}":"[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))throw new GeneralSecurityException("Invalid transfer record ID");
        byte[] raw=Crypto.unb64(row.getString("data"));try{if(raw.length>RecordCodec.MAX_BYTES)throw new IOException("Transfer record too large");if(table.equals("seen")){if(!new String(raw,Crypto.UTF8).matches("[a-f0-9]{64}"))throw new GeneralSecurityException("Invalid transferred receipt");}else if(table.equals("messages")){byte[] json=RecordCodec.decode(raw);try{JSONObject message=transferObject(json),envelope=message.getJSONObject("envelope");if(!id.equals(envelope.getString("id"))||!owner.equals(envelope.getString(message.getBoolean("outgoing")?"sender":"recipient")))throw new GeneralSecurityException("Transferred message belongs to another account");}finally{Arrays.fill(json,(byte)0);}}else transferObject(raw);}finally{Arrays.fill(raw,(byte)0);}return row;
    }
    static void verifyTransfer(File file,byte[] key,JSONObject profile)throws Exception{
        try(InputStream authenticated=new FileInputStream(file)){TransferArchive.read(authenticated,key,profile.getString("uid"),profile.getString("fingerprint"),null,(value,signature)->Crypto.verifySignature(profile,value,Crypto.b64(signature)),()->{});}
        boolean[] metadata={false};try(InputStream input=new FileInputStream(file)){TransferArchive.read(input,key,profile.getString("uid"),profile.getString("fingerprint"),bytes->{JSONObject row=transferRecord(bytes,profile.getString("uid"));if(row.getString("table").equals("meta")){if(metadata[0])throw new IOException("Duplicate transfer metadata");metadata[0]=true;}else if(!metadata[0])throw new IOException("Missing transfer metadata");},(value,signature)->Crypto.verifySignature(profile,value,Crypto.b64(signature)),()->{});}if(!metadata[0])throw new IOException("Missing transfer metadata");
    }
    int importTransfer(File file,byte[] key)throws Exception{
        if(transferIn()==null||account()==null||!account().getString("uid").equals(transferIn().getString("uid")))throw new SecurityException("Finish the account move first.");String owner=account().getString("uid");JSONObject before=new JSONObject(meta.toString());JSONObject[] metadata={null};int[] count={0};
        db.beginTransaction();try{
            try(InputStream input=new FileInputStream(file)){TransferArchive.read(input,key,owner,identity().getString("fingerprint"),bytes->{JSONObject row=transferRecord(bytes,owner);String table=row.getString("table"),id=row.getString("id");if(table.equals("meta")){if(metadata[0]!=null)throw new IOException("Duplicate transfer metadata");metadata[0]=row.getJSONObject("data");return;}if(metadata[0]==null)throw new IOException("Missing transfer metadata");String purpose=table.equals("messages")?"message:":table.equals("seen")?"seen:":"change:";byte[] plain=Crypto.unb64(row.getString("data"));try{try(Cursor prior=db.rawQuery("SELECT payload FROM "+table+" WHERE id=?",new String[]{id})){if(prior.moveToFirst()){byte[] old=crypto.open(prior.getBlob(0),purpose+id);try{if(!MessageDigest.isEqual(old,plain))throw new GeneralSecurityException("Transfer conflicts with saved history");}finally{Arrays.fill(old,(byte)0);}return;}}ContentValues values=new ContentValues();values.put("id",id);values.put("payload",crypto.seal(plain,purpose+id));if(db.insertOrThrow(table,null,values)==-1)throw new IOException("Could not save transferred history");count[0]++;}finally{Arrays.fill(plain,(byte)0);}},(value,signature)->Crypto.verifySignature(accountWithIdentity(),value,Crypto.b64(signature)),()->{});}
            if(metadata[0]==null)throw new IOException("Missing transfer metadata");rebuildIndexes();for(String name:new String[]{"contacts","groups","settings"})if(metadata[0].has(name))meta.put(name,metadata[0].get(name));meta.getJSONObject("settings").put("notifications",false);meta.put("groupInvites",new JSONArray());saveMeta();db.setTransactionSuccessful();revision++;return count[0];
        }catch(Exception e){meta=before;saveMeta();throw e;}finally{db.endTransaction();}
    }
    void historySaved()throws Exception{JSONObject pending=transferIn();if(pending==null)throw new SecurityException("No move is pending");pending.put("historySaved",true);transferIn(pending);}
    JSONObject accountWithIdentity()throws Exception{return new JSONObject(account().toString()).put("delegations",identity().optJSONArray("delegations")==null?new JSONArray():identity().getJSONArray("delegations"));}
    JSONObject accountPolicy(){return meta.optJSONObject("accountPolicy");}
    void accountPolicy(JSONObject value)throws Exception{int days=value.getInt("days");if(days!=0&&days!=1&&days!=7&&days!=365)throw new GeneralSecurityException("Invalid inactivity policy");JSONObject saved=new JSONObject(value.toString()).put("receivedAt",System.currentTimeMillis());meta.put("accountPolicy",saved);saveMeta();}
    boolean policyNeedsCheck(){JSONObject p=accountPolicy();if(p==null||p.optInt("days")==0)return false;long adjusted=System.currentTimeMillis()+p.optLong("serverTime")-p.optLong("receivedAt");return adjusted>=p.optLong("lastActive")+p.optInt("days")*86400000L;}
    JSONObject transferOut(){return meta.optJSONObject("transferOut");}
    JSONObject transferIn(){return meta.optJSONObject("transferIn");}
    void transferOut(JSONObject value)throws Exception{meta.put("transferOut",value==null?JSONObject.NULL:value);saveMeta();}
    void transferIn(JSONObject value)throws Exception{meta.put("transferIn",value==null?JSONObject.NULL:value);saveMeta();}
    boolean emptyForTransfer()throws Exception{if(account()!=null||session()!=null||!contacts().isEmpty()||!groups().isEmpty())return false;for(String table:new String[]{"messages","seen","message_changes"})try(Cursor c=db.rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();if(c.getInt(0)>0)return false;}return true;}
    void activateTransfer(JSONObject incoming,JSONObject profile)throws Exception{
        if(account()!=null&&incoming.getString("uid").equals(account().getString("uid"))&&identity().getString("fingerprint").equals(profile.getString("fingerprint")))return;
        if(!emptyForTransfer())throw new GeneralSecurityException("This phone already has an account or history. Use an empty installation.");Crypto.verifyProfile(profile);JSONObject source=incoming.getJSONObject("identity"),next=new JSONObject(identity().toString());
        for(String key:new String[]{"private","hpke","signing","fingerprint","historySecret"})next.put(key,source.getString(key));next.put("delegations",profile.getJSONArray("delegations"));Crypto.verifyPrivateIdentity(next);
        if(!profile.getString("uid").equals(incoming.getString("uid"))||!profile.getString("fingerprint").equals(next.getString("fingerprint")))throw new GeneralSecurityException("Transfer account mismatch");
        meta.put("identity",next).put("account",profile);saveMeta();
    }
    public void close() { db.close(); }
    public void destroy() throws Exception { destroyed=true;meta=new JSONObject();revision++;db.close();crypto.destroy();erase(directory); }
    private static void erase(File file) throws IOException { if (file.isDirectory()) { File[] files = file.listFiles(); if (files != null) for (File child : files) erase(child); } if (file.exists() && !file.delete()) throw new IOException("Could not remove private files"); }
}
