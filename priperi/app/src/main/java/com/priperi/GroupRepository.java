package com.priperi;

import org.json.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** Group copies use the existing HPKE keys; membership is checked again atomically by the relay. */
final class GroupRepository {
    static void checkId(String value)throws GeneralSecurityException {if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw new GeneralSecurityException("Invalid group identifier");}
    static boolean validName(String value){return value!=null&&value.length()>0&&value.length()<=60&&value.equals(value.trim())&&!value.matches("(?s).*[\\p{Cntrl}\\u0080-\\u009f].*");}
    static JSONObject member(JSONObject group,String uid)throws JSONException {JSONArray members=group.getJSONArray("members");for(int i=0;i<members.length();i++)if(uid.equals(members.getJSONObject(i).optString("uid")))return members.getJSONObject(i);return null;}
    static boolean admin(JSONObject group)throws JSONException {JSONObject me=member(group,Session.uid());return group.optBoolean("active")&&me!=null&&me.optBoolean("admin");}
    static JSONObject action(Vault v,String operation,JSONObject payload)throws Exception {return Repository.call(v,operation,payload);}
    static JSONObject create(Vault v,String id,String name)throws Exception {if(!validName(name))throw new IllegalArgumentException("Use a group name of up to 60 characters.");return action(v,"createGroup",new JSONObject().put("id",id).put("name",name));}
    static boolean keysVerified(Vault v,JSONObject group)throws Exception{if(group==null)return false;JSONArray members=group.getJSONArray("members");for(int i=0;i<members.length();i++){JSONObject peer=members.getJSONObject(i);if(!peer.getString("uid").equals(Session.uid())&&!v.isVerified(peer))return false;}return true;}
    static String roster(JSONObject group)throws Exception{List<String> members=new ArrayList<>();JSONArray values=group.getJSONArray("members");for(int i=0;i<values.length();i++){JSONObject peer=values.getJSONObject(i);members.add(peer.getString("uid")+":"+peer.getString("fingerprint")+":"+peer.optBoolean("admin"));}Collections.sort(members);return TransferCipher.hash(("priperi-roster-v1\n"+group.getString("id")+"\n"+String.join("\n",members)).getBytes(Crypto.UTF8));}
    static JSONObject send(Vault v,String gid,String text)throws Exception {
        MessageContent.parse(text);
        JSONObject group=v.group(gid);if(group==null||!group.optBoolean("active")||member(group,Session.uid())==null)throw new SecurityException("You’re no longer in this group.");
        JSONArray members=group.getJSONArray("members"),copies=new JSONArray();String id=UUID.randomUUID().toString();long at=System.currentTimeMillis();
        for(int i=0;i<members.length();i++){JSONObject peer=members.getJSONObject(i);if(!Session.uid().equals(peer.getString("uid")))copies.put(v.crypto().encryptGroup(Session.uid(),v.identity(),peer,text,id,at,gid,group.getInt("version")));}
        if(copies.length()==0)throw new SecurityException("Invite someone before sending a group message.");
        JSONObject record=new JSONObject().put("peer","group:"+gid).put("body",text).put("outgoing",true).put("state","pending").put("envelope",copies.getJSONObject(0)).put("groupDeliveries",copies);
        MessageActions.decode(record,text);v.store(record);return record;
    }
    static JSONObject tags(Vault v)throws Exception {JSONObject tags=new JSONObject();for(JSONObject g:v.groups())if(g.optBoolean("active")&&g.optString("tag").matches("[a-f0-9]{32}"))tags.put(g.getString("id"),g.getString("tag"));return tags;}
    static JSONObject receive(Vault v,JSONObject data)throws Exception {
        java.util.Set<String> oldInvites=new java.util.HashSet<>();for(int j=0;j<v.groupInvites().length();j++)oldInvites.add(v.groupInvites().getJSONObject(j).optString("id"));v.applyGroups(data);java.util.Set<String> alertPeers=new java.util.HashSet<>();for(int j=0;j<v.groupInvites().length();j++)if(!oldInvites.contains(v.groupInvites().getJSONObject(j).optString("id")))alertPeers.add("group:"+v.groupInvites().getJSONObject(j).optString("groupId"));String warning="";JSONArray messages=data.getJSONArray("messages"),saved=new JSONArray();
        for(int i=0;i<messages.length();i++){
            JSONObject envelope=messages.getJSONObject(i);
            try{
                String gid=envelope.getString("group");JSONObject group=v.group(gid);if(group==null||!group.optBoolean("active")||envelope.getInt("groupVersion")>group.getInt("version"))throw new GeneralSecurityException("Invalid group membership");
                JSONObject sender=member(group,envelope.getString("sender")),blocked=v.contact(envelope.getString("sender"));
                if(sender==null||(blocked!=null&&blocked.optBoolean("blocked")))throw new GeneralSecurityException("Group sender unavailable");
                if(!v.seen(envelope)){
                    String text;boolean unsupported=false;try{text=v.crypto().decrypt(Session.uid(),v.identity(),sender,envelope);}catch(Crypto.SignedCiphertextInvalid invalid){text="This message could not be decrypted.";unsupported=true;warning="An unreadable group message was saved safely.";}catch(MessageContent.InvalidContent invalid){text="Unsupported message. Update Priperi to view it.";unsupported=true;warning="An unsupported group message was saved safely.";}
                    JSONObject record=new JSONObject().put("peer","group:"+gid).put("outgoing",false).put("state","received").put("envelope",envelope).put("author",sender);if(unsupported)record.put("body",text).put("unsupported",true);else MessageActions.decode(record,text);try{v.store(record);if(!record.optBoolean("hidden"))alertPeers.add("group:"+gid);}catch(MessageActions.InvalidChange invalid){v.discardChange(record);warning="An invalid group update was ignored.";}
                }
                saved.put(envelope.getString("id"));
            }catch(GeneralSecurityException|SecurityException e){warning="A group message failed a security check and was left on the server.";}
        }
        return new JSONObject().put("ids",saved).put("warning",warning).put("alertPeers",new JSONArray(alertPeers));
    }
}
