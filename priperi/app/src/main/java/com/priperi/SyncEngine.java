package com.priperi;

import org.json.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** Network orchestration owns no mutable vault state. Each local stage stays on Repository's actor. */
final class SyncEngine {
    interface Progress {void saved(String user,String warning,long revision);}
    private final Vault inline;
    private final Repository.Lease lease;
    private final Progress progress;private final Set<String> alerts=new HashSet<>();
    private static int receiptCursor,contactCursor; // accessed only in local actor stages
    SyncEngine(Vault vault){inline=vault;lease=null;progress=null;}
    SyncEngine(Progress progress)throws Exception{inline=null;lease=Repository.stage(null,Repository.Lease::new);this.progress=progress;}
    private <T>T local(Repository.Job<T> job)throws Exception{return inline!=null?job.run(inline):Repository.stage(lease,job);}
    private JSONObject call(String operation,JSONObject payload)throws Exception{return inline!=null?Repository.call(inline,operation,payload):Repository.exchange(lease,operation,payload);}
    private void changed(String warning)throws Exception{
        if(progress!=null){long revision=local(Vault::revision);progress.saved(lease.uid,warning,revision);}
    }
    void presence()throws Exception{
        if(inline!=null||!local(AccountPolicy::claimForeground))return;
        try{JSONObject policy=call("accountPresence",new JSONObject());if(policy.has("days"))local(v->{v.accountPolicy(policy);return true;});}
        catch(Session.BackendException error){if(error.status!=404)throw error;}
    }
    String user(){return lease.uid;}
    long revision()throws Exception{return local(Vault::revision);}
    String inbox()throws Exception{
        if(local(v->v.account()==null))return "";
        if(local(v->v.transferOut()!=null))return "Account transfer is pending.";
        presence();
        JSONObject payload=local(v->{
            List<JSONObject> records=v.workRecords();List<String> waiting=new ArrayList<>();JSONArray receiptIds=new JSONArray(),contactIds=new JSONArray();
            for(JSONObject m:records)if(!m.optString("peer").startsWith("group:")&&"sent".equals(m.optString("state")))waiting.add(m.getJSONObject("envelope").getString("id"));
            if(!waiting.isEmpty()){int start=receiptCursor%waiting.size(),count=Math.min(100,waiting.size());for(int i=0;i<count;i++)receiptIds.put(waiting.get((start+i)%waiting.size()));receiptCursor=(start+count)%waiting.size();}
            List<JSONObject> contacts=v.contacts();if(!contacts.isEmpty()){int start=contactCursor%contacts.size(),count=Math.min(50,contacts.size());for(int i=0;i<count;i++)contactIds.put(contacts.get((start+i)%contacts.size()).getString("uid"));contactCursor=(start+count)%contacts.size();}
            return new JSONObject().put("receiptIds",receiptIds).put("contactIds",contactIds).put("groupTags",GroupRepository.tags(v));
        });
        JSONObject inbox=call("syncInbox",payload);String warning="";JSONArray messages=inbox.getJSONArray("messages"),saved=new JSONArray();
        for(int i=0;i<messages.length();i++){
            JSONObject envelope=messages.getJSONObject(i);String senderId=envelope.getString("sender");
            try{
                if(!local(v->v.seen(envelope))){
                    JSONObject sender=local(v->{JSONObject p=v.contact(senderId);return p==null?null:new JSONObject(p.toString());});
                    if(sender==null){JSONObject included=profile(inbox,senderId),fresh=included==null?call("lookupUser",new JSONObject().put("uid",senderId)):included;sender=local(v->{v.pin(fresh);return new JSONObject(v.contact(senderId).toString());});}
                    if(sender.optBoolean("blocked"))continue;
                    try{String note=receive(envelope,sender);if(!note.isEmpty())warning=note;}
                    catch(GeneralSecurityException changed){JSONObject included=profile(inbox,senderId),fresh=included==null?call("lookupUser",new JSONObject().put("uid",senderId)):included;JSONObject updated=local(v->{v.pin(fresh);return new JSONObject(v.contact(senderId).toString());});String note=receive(envelope,updated);if(!note.isEmpty())warning=note;}
                }
                saved.put(envelope.getString("id"));
            }catch(GeneralSecurityException|SecurityException error){warning="A message failed a security check. It was left on the server.";}
        }
        JSONObject group=inbox.optJSONObject("groupData"),groupSaved=group==null?null:local(v->GroupRepository.receive(v,group));
        if(groupSaved!=null&&!groupSaved.getString("warning").isEmpty())warning=groupSaved.getString("warning");
        boolean newlyApproved=local(v->{boolean accepted=false;
            JSONArray receipts=inbox.getJSONArray("receipts");
            for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i),record=v.message(receipt.getString("id"));if(record!=null&&record.optBoolean("outgoing")&&receipt.getString("recipient").equals(record.optString("peer"))){record.put("state","delivered");v.store(record);}}
            JSONArray states=inbox.optJSONArray("contactStates");if(states!=null)for(int i=0;i<states.length();i++){JSONObject state=states.getJSONObject(i),before=v.contact(state.getString("uid")),permission=before==null?null:before.optJSONObject("contactState");if(before!=null&&state.optBoolean("approved")&&(permission==null||!permission.optBoolean("approved")))accepted=true;v.contactState(state);}return accepted;
        });
        if(newlyApproved&&inline==null)SyncCoordinator.retryApprovedOutbox(lease.uid);
        if(groupSaved!=null){JSONArray peers=groupSaved.optJSONArray("alertPeers");if(peers!=null)for(int i=0;i<peers.length();i++)alerts.add(peers.getString(i));}
        if(!alerts.isEmpty()&&inline==null)Notices.received(lease.uid,new HashSet<>(alerts));
        // Show committed messages before waiting for delivery receipts to reach the server.
        changed(warning);
        if(saved.length()>0){try{call("acknowledgeMessages",new JSONObject().put("ids",saved));}catch(Session.BackendException error){if(error.status!=404)throw error;for(int i=0;i<saved.length();i++)call("acknowledgeMessage",new JSONObject().put("id",saved.getString(i)));}}
        if(groupSaved!=null&&groupSaved.getJSONArray("ids").length()>0)call("ackGroupMessages",new JSONObject().put("ids",groupSaved.getJSONArray("ids")));
        return warning;
    }
    private static JSONObject profile(JSONObject inbox,String uid)throws Exception{
        JSONArray profiles=inbox.optJSONArray("senderProfiles");if(profiles==null)return null;
        if(profiles.length()>50)throw new GeneralSecurityException("Invalid sender list");
        for(int i=0;i<profiles.length();i++)if(uid.equals(profiles.getJSONObject(i).optString("uid")))return profiles.getJSONObject(i);
        return null;
    }
    private String receive(JSONObject envelope,JSONObject sender)throws Exception{
        return local(v->{
            // Re-check mutable block state after the network wait.
            JSONObject pinned=v.contact(sender.getString("uid"));if(pinned==null||pinned.optBoolean("blocked"))throw new SecurityException("Contact unavailable");
            String body,warning="";boolean unsupported=false;
            try{body=v.crypto().decrypt(Session.uid(),v.identity(),sender,envelope);}catch(Crypto.SignedCiphertextInvalid invalid){body="This message could not be decrypted.";unsupported=true;warning="An unreadable message was saved safely.";}catch(MessageContent.InvalidContent invalid){body="Unsupported message. Update Priperi to view it.";unsupported=true;warning="An unsupported message was saved safely. Update Priperi to view it.";}
            JSONObject record=new JSONObject().put("peer",sender.getString("uid")).put("body",body).put("envelope",envelope).put("outgoing",false).put("state","received");
            if(unsupported)record.put("unsupported",true);else MessageActions.decode(record,body);
            try{v.store(record);if(!record.optBoolean("hidden"))alerts.add(sender.getString("uid"));}catch(MessageActions.InvalidChange invalid){v.discardChange(record);warning="An invalid message update was ignored.";}return warning;
        });
    }
    String outbox()throws Exception{
        if(local(v->v.account()==null||v.transferOut()!=null))return "";
        presence();List<JSONObject> records=local(Vault::workRecords);String warning="";int direct=0,groups=0;
        long now=System.currentTimeMillis();Set<String> deferred=new HashSet<>();
        // Keep a rejected conversation in order, without holding up other
        // conversations or retrying the same rejection on every inbox poll.
        for(JSONObject record:records){long retry=record.optLong("retryAfter");if("pending".equals(record.optString("state"))&&retry>now&&retry-now<=60_000)deferred.add(record.getString("peer"));}
        for(JSONObject snapshot:records){
            String id=snapshot.getJSONObject("envelope").getString("id");
            JSONObject record=local(v->{JSONObject m=v.message(id);return m!=null&&("pending".equals(m.optString("state"))||"sent".equals(m.optString("state")))?m:null;});if(record==null)continue;
            JSONObject envelope=record.getJSONObject("envelope");boolean group=record.optString("peer").startsWith("group:");
            if(System.currentTimeMillis()-envelope.getLong("sentAt")>=7L*24*60*60*1000){local(v->{JSONObject m=v.message(id);if(m!=null&&("pending".equals(m.optString("state"))||"sent".equals(m.optString("state")))){m.put("state","expired");m.remove("groupDeliveries");v.store(m);}return true;});continue;}
            if(!"pending".equals(record.optString("state")))continue;
            if(deferred.contains(record.getString("peer")))continue;
            if(group&&!local(v->{JSONObject g=v.group(envelope.getString("group"));return g!=null&&g.optBoolean("active")&&g.optInt("version")==envelope.optInt("groupVersion");})){finish(id,"group-changed",null);warning="The group changed. An unsent message is saved; review the members and send it again.";continue;}
            if(group){if(groups>=2)continue;groups++;}
            else{if(direct>=10)continue;boolean waiting=local(v->{JSONObject c=v.contact(record.getString("peer")),p=c==null?null:c.optJSONObject("contactState");return c==null||c.optBoolean("blocked")||record.optBoolean("waitingApproval")&&p!=null&&!p.optBoolean("approved");});if(waiting)continue;direct++;}
            try{
                JSONObject state=call(group?"sendGroupMessage":"sendMessage",group?new JSONObject().put("groupId",envelope.getString("group")).put("messages",record.getJSONArray("groupDeliveries")):new JSONObject().put("message",envelope));
                finish(id,group?"sent":state.getString("state"),state.optJSONObject("contactState"));changed(warning);
            }catch(Session.BackendException error){
                if(group&&"group-changed".equals(error.reason)){finish(id,"group-changed",null);warning="The group changed. An unsent message is saved; review the members and send it again.";}
                else if(!group&&"contact-approval-required".equals(error.reason)){
                    local(v->{JSONObject m=v.message(id),c=v.contact(record.getString("peer")),p=c==null?null:c.optJSONObject("contactState");v.contactState(new JSONObject().put("uid",record.getString("peer")).put("sentCount",3).put("approved",false).put("incomingPending",p!=null&&p.optBoolean("incomingPending")));if(m!=null&&"pending".equals(m.optString("state"))){m.put("waitingApproval",true);v.store(m);}return true;});warning="A contact needs to approve you before more messages can be sent.";
                }else if(Session.uid()!=null&&(error.status==400||error.status==403||error.status==404||error.status==409||error.status==429&&"resource-exhausted".equals(error.reason))){
                    // These may be a missing/blocked contact, stale envelope or
                    // paused access. Preserve the exact envelope for safe retry;
                    // never turn a rejection into a successful delivery.
                    local(v->{JSONObject m=v.message(id);if(m!=null&&"pending".equals(m.optString("state"))){m.put("retryAfter",System.currentTimeMillis()+60_000);v.store(m);}return true;});
                    deferred.add(record.getString("peer"));warning="A message could not be sent. "+(error.status==403?"":Repository.friendly(error)+" ")+"It is saved and will retry shortly.";
                }else throw error;
            }
        }
        return warning;
    }
    private void finish(String id,String state,JSONObject permission)throws Exception{
        local(v->{
            // Update the latest row, preserving edits/deletions/receipts that committed during HTTP.
            JSONObject m=v.message(id);if(m!=null&&"pending".equals(m.optString("state"))){m.put("state",state).put("approvalTracked",true);m.remove("waitingApproval");m.remove("retryAfter");m.remove("groupDeliveries");v.store(m);}
            if(permission!=null)v.contactState(permission);return true;
        });
    }
}
