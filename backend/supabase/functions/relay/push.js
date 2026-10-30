import { createSign } from 'node:crypto';
import { Buffer } from 'node:buffer';
let cached;
const encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
export async function push(token) {
 const raw=Deno.env.get('FCM_SERVICE_ACCOUNT');if(!raw)return;
 const account=JSON.parse(raw);let access=cached?.expires>Date.now()+60000?cached.token:null;
 if(!access) {
  const now=Math.floor(Date.now()/1000),unsigned=encode({alg:'RS256',typ:'JWT',kid:account.private_key_id})+'.'+encode({iss:account.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3300});
  const signature=createSign('RSA-SHA256').update(unsigned).sign(account.private_key).toString('base64url');
  const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:unsigned+'.'+signature}),signal:AbortSignal.timeout(12000)});
  if(!response.ok)return;const data=await response.json();access=data.access_token;cached={token:access,expires:Date.now()+data.expires_in*1000};
 }
 const response=await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+access,'Content-Type':'application/json'},body:JSON.stringify({message:{token,data:{kind:'inbox'},android:{priority:'high',ttl:'3600s',collapse_key:'inbox'}}}),signal:AbortSignal.timeout(12000)});
 await response.body?.cancel();
}
