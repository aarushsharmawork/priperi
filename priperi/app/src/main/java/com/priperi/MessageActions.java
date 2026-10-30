package com.priperi;

import org.json.*;
import java.security.GeneralSecurityException;

/** Target ownership comes only from the authenticated envelope, not a claimed JSON author. */
final class MessageActions {
    static final class InvalidChange extends GeneralSecurityException {InvalidChange(String message){super(message);}}
    static JSONObject decode(JSONObject record,String wire)throws Exception {
        MessageContent m=MessageContent.parse(wire);record.put("body",m.text);
        if(m.replyTo!=null)record.put("replyTo",m.replyTo);
        if(m.kind!=1)record.put("hidden",true).put("change",new JSONObject().put("kind",m.kind).put("target",m.target).put("targetAt",m.targetAt).put("groupVersion",m.groupVersion).put("text",m.text));
        return record;
    }
    static JSONObject send(Vault v,String peer,String text,String reply)throws Exception {
        if(reply!=null){JSONObject target=v.message(reply);if(target==null||!peer.equals(target.optString("peer"))||target.optBoolean("deleted")||target.optBoolean("localDeleted")||target.optBoolean("hidden"))throw new SecurityException("That message is no longer available to reply to.");}
        String wire=MessageContent.text(text,reply);
        return peer.startsWith("group:")?GroupRepository.send(v,peer.substring(6),wire):Repository.send(v,peer,wire);
    }
    static JSONObject change(Vault v,String peer,String id,String text,boolean delete)throws Exception {
        JSONObject m=v.message(id);String me=Session.uid();
        if(me==null||v.account()==null||!me.equals(v.account().optString("uid"))||m==null||!peer.equals(m.optString("peer"))||!m.optBoolean("outgoing")||m.optBoolean("deleted")||m.optBoolean("localDeleted")||m.optBoolean("hidden")||!("sent".equals(m.optString("state"))||"delivered".equals(m.optString("state"))))throw new SecurityException("Only your sent messages can be changed.");
        JSONObject envelope=m.getJSONObject("envelope");if(!me.equals(envelope.getString("sender")))throw new SecurityException("Invalid message owner");
        long now=System.currentTimeMillis();if(!MessageContent.allowed(delete?3:2,envelope.getLong("sentAt"),now))throw new SecurityException(delete?"Messages can be removed for everyone within 24 hours.":"Messages can be edited within 15 minutes.");
        if(peer.startsWith("group:")){JSONObject group=v.group(peer.substring(6));if(group==null||!group.optBoolean("active")||group.getInt("version")!=envelope.getInt("groupVersion"))throw new SecurityException("The group changed. You can still delete this copy from your phone.");}
        String wire=MessageContent.change(delete,id,envelope.getLong("sentAt"),envelope.optInt("groupVersion"),text);
        return peer.startsWith("group:")?GroupRepository.send(v,peer.substring(6),wire):Repository.send(v,peer,wire);
    }
    static JSONObject mutation(JSONObject event)throws Exception {
        JSONObject c=event.getJSONObject("change"),env=event.getJSONObject("envelope");int kind=c.getInt("kind");
        if((kind!=2&&kind!=3)||!MessageContent.allowed(kind,c.getLong("targetAt"),env.getLong("sentAt"))||c.getInt("groupVersion")!=env.optInt("groupVersion")||c.getString("target").equals(env.getString("id")))throw new InvalidChange("Invalid message change");
        return new JSONObject(c.toString()).put("peer",event.getString("peer")).put("sender",env.getString("sender")).put("fingerprint",env.getString("senderFingerprint")).put("at",env.getLong("sentAt")).put("event",env.getString("id"));
    }
    static void apply(JSONObject record,JSONObject change)throws Exception {
        JSONObject env=record.getJSONObject("envelope");if(!record.getString("peer").equals(change.getString("peer"))||!env.getString("sender").equals(change.getString("sender"))||!env.getString("senderFingerprint").equals(change.getString("fingerprint"))||env.getLong("sentAt")!=change.getLong("targetAt")||env.optInt("groupVersion")!=change.getInt("groupVersion"))throw new InvalidChange("Message change does not match its author or chat");
        record.put("changeState",change.optString("state"));
        if(change.getInt("kind")==3){record.put("deleted",true).put("body","");record.remove("replyTo");record.remove("edited");}else if(!record.optBoolean("deleted"))record.put("body",change.getString("text")).put("edited",true);
    }
    private MessageActions(){}
}
