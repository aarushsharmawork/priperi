import {PGlite} from '@electric-sql/pglite';
import {readFileSync} from 'node:fs';
export async function database({contactApproval=true,securityHardening=true,accountControls=true,syncOptimization=true,reinstallReplacement=true,recoveryKits=true,conversationApproval=true,signupProtection=false}={}){
 const db=new PGlite();
 await db.exec(`CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role; CREATE SCHEMA auth;
 CREATE TABLE auth.users(id uuid PRIMARY KEY,email text,deleted_at timestamptz,banned_until timestamptz);
 CREATE TABLE auth.sessions(id uuid PRIMARY KEY,user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE);`);
 await db.exec(readFileSync(new URL('../migrations/202610260001_relay.sql',import.meta.url),'utf8'));
 await db.exec(readFileSync(new URL('../migrations/202610260004_device_access.sql',import.meta.url),'utf8'));
 if(contactApproval)await db.exec(readFileSync(new URL('../migrations/202610270005_contact_approval.sql',import.meta.url),'utf8'));
 if(contactApproval)await db.exec(readFileSync(new URL('../migrations/202610270006_contact_names.sql',import.meta.url),'utf8'));
 if(contactApproval)await db.exec(readFileSync(new URL('../migrations/202610270007_groups.sql',import.meta.url),'utf8'));
 if(contactApproval)await db.exec(readFileSync(new URL('../migrations/202610270008_group_cache.sql',import.meta.url),'utf8'));
 if(contactApproval)await db.exec(readFileSync(new URL('../migrations/202610270009_group_storage_caps.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening)await db.exec(readFileSync(new URL('../migrations/202610270010_security_hardening.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening&&accountControls)await db.exec(readFileSync(new URL('../migrations/202610270011_account_controls.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening&&accountControls)await db.exec(readFileSync(new URL('../migrations/202610280012_transfer_cleanup.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening&&accountControls&&syncOptimization)await db.exec(readFileSync(new URL('../migrations/202610280013_sync_latency.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening&&accountControls&&syncOptimization&&reinstallReplacement)await db.exec(readFileSync(new URL('../migrations/202610280014_reinstall_replacement.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening&&accountControls&&syncOptimization&&reinstallReplacement&&recoveryKits)await db.exec(readFileSync(new URL('../migrations/202610280015_recovery_kits.sql',import.meta.url),'utf8'));
 if(contactApproval&&securityHardening&&accountControls&&syncOptimization&&reinstallReplacement&&recoveryKits&&conversationApproval)await db.exec(readFileSync(new URL('../migrations/202610280016_conversation_approval.sql',import.meta.url),'utf8'));
 if(signupProtection)await db.exec(readFileSync(new URL('../migrations/202610300017_signup_admission.sql',import.meta.url),'utf8'));
 const signatures={priperi_signup_admit:['token_hash','proof','installation_id','device_id_value','account_name','fingerprint_value'],priperi_recovery:['actor','session_id','proof','operation','payload','recent_password'],priperi_replacement:['proof','installation_id','device_id_value','fingerprint_value','action','payload'],priperi_auth_seed:['actor','proof','candidate'],priperi_account:['actor','session_id','proof','operation','payload','recent_password'],priperi_transfer:['actor','session_id','proof','operation','payload','recent_password'],priperi_groups:['actor','session_id','proof','operation','payload'],priperi_device:['proof','installation_id','fingerprint_value','device_id_value','action','payload'],priperi_begin_device:['name','proof','installation_id','hpke_key','signing_key','fingerprint_value','expected_actor','device_id_value'],priperi_search:['actor','session_id','proof','query'],priperi_begin:['name','proof','installation_id','hpke_key','signing_key','fingerprint_value','expected_actor'],priperi_finish:['actor','proof'],priperi_attempt:['actor','session_id','proof','kind'],priperi_request:['actor','session_id','proof','operation','payload','recent_password']};
 return {db,async rpc(name,args){try{const fields=signatures[name],r=await db.query(`SELECT public.${name}(${fields.map((_,i)=>'$'+(i+1)).join(',')}) AS value`,fields.map(k=>k==='payload'?JSON.stringify(args[k]):args[k]));return {data:r.rows[0].value,error:null};}catch(error){if(process.env.PRIPERI_TRACE)console.error(error.message,error.internalQuery,error.where);return {data:null,error};}}};
}
