package com.priperi;
import org.json.*;
/** Explicit trust for synthetic identities whose keys the test itself generated. Never shipped in APKs. */
final class TestIdentityTrust {
 static void pin(Vault v,JSONObject peer)throws Exception{v.pin(peer);v.contactFlag(peer.getString("uid"),"verified",true);}
 static void unverify(Vault v,String uid)throws Exception{java.lang.reflect.Field field=Vault.class.getDeclaredField("meta");field.setAccessible(true);((JSONObject)field.get(v)).getJSONObject("settings").remove("trusted-key:"+uid);v.setSetting("unverifiedLayoutProbe",true);}
 static void trustGroup(Vault v,String gid)throws Exception{JSONObject group=v.group(gid);if(group==null||!group.optBoolean("active"))return;JSONArray members=group.getJSONArray("members");for(int i=0;i<members.length();i++)v.verifyIdentity(members.getJSONObject(i));v.verifyGroup(group);}
 static void groups(Vault v,JSONObject data)throws Exception{v.applyGroups(data);for(JSONObject group:v.groups())if(group.optBoolean("active"))trustGroup(v,group.getString("id"));}
 static JSONObject sendGroup(Vault v,String gid,String text)throws Exception{trustGroup(v,gid);return GroupRepository.send(v,gid,text);}
 static JSONObject send(Vault v,String peer,String text,String reply)throws Exception{if(peer.startsWith("group:"))trustGroup(v,peer.substring(6));return MessageActions.send(v,peer,text,reply);}
 static JSONObject change(Vault v,String peer,String id,String text,boolean delete)throws Exception{if(peer.startsWith("group:"))trustGroup(v,peer.substring(6));return MessageActions.change(v,peer,id,text,delete);}
 private TestIdentityTrust(){}
}
