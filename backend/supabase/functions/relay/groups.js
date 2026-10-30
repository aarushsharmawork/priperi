import * as p from './policy.js';
import {verifyAuthorized} from './delegation.js';
export const groupKinds={createGroup:'settings',renameGroup:'settings',inviteGroupMember:'settings',acceptGroupInvite:'settings',declineGroupInvite:'settings',removeGroupMember:'settings',setGroupAdmin:'settings',leaveGroup:'settings',sendGroupMessage:'send',ackGroupMessages:'ack'};
const id=value=>p.messageId(value);
export function groupName(value){p.text(value,1,60);p.requireThat(value===value.trim()&&!/[\u0000-\u001f\u007f-\u009f]/.test(value));return value;}
export async function groupPayload(operation,data,actor,account) {
 const payload={};
 if(['createGroup','acceptGroupInvite','declineGroupInvite','inviteGroupMember'].includes(operation))payload.id=id(data.id);
 if(['createGroup','renameGroup'].includes(operation))payload.name=groupName(data.name);
 if(!['createGroup','acceptGroupInvite','declineGroupInvite','ackGroupMessages'].includes(operation))payload.groupId=id(data.groupId);
 if(['inviteGroupMember','removeGroupMember','setGroupAdmin'].includes(operation))payload.uid=id(data.uid);
 if(operation==='setGroupAdmin'){p.requireThat(typeof data.admin==='boolean');payload.admin=data.admin;}
 if(operation==='ackGroupMessages'){p.requireThat(Array.isArray(data.ids)&&data.ids.length>0&&data.ids.length<=25&&new Set(data.ids).size===data.ids.length);payload.ids=data.ids.map(id);}
 if(operation==='sendGroupMessage'){
  p.requireThat(Array.isArray(data.messages)&&data.messages.length>0&&data.messages.length<=19);
  const me=await account(),messages=data.messages.map(m=>p.validateEnvelope(m,Date.now(),true));
  const first=messages[0];
  for(const m of messages){
   p.requireThat(m.sender===actor&&m.group===payload.groupId&&m.id===first.id&&m.sentAt===first.sentAt&&m.groupVersion===first.groupVersion,'permission-denied','This group message is unavailable.');
   id(m.sender);id(m.recipient);verifyAuthorized(m.signature,p.context(m)+'\n'+m.ciphertext,me);
  }
  p.requireThat(new Set(messages.map(m=>m.recipient)).size===messages.length);
  messages.sort((a,b)=>a.recipient.localeCompare(b.recipient));
  payload.messages=messages;payload.version=first.groupVersion;
  payload.digest=p.hash(messages.map(m=>p.context(m)+'\n'+m.ciphertext+'\n'+m.signature).join('\n'));
 }
 return payload;
}
