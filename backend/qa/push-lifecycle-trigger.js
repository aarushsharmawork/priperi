if(process.env.PRIPERI_PUSH_TEST!=='yes'||!process.env.PRIPERI_EXPECTED_HOSTED_URL?.startsWith('https://')||!process.env.PRIPERI_HOSTED_PUBLISHABLE_KEY?.startsWith('sb_publishable_'))throw Error('Explicit disposable push-test target and public key required');
import {tmpdir} from 'node:os';
import {join} from 'node:path';
// Opt-in disposable-emulator test helper. Never prints the scoped test credentials.
import {spawnSync} from 'node:child_process';
import {mkdirSync,writeFileSync,readFileSync,unlinkSync} from 'node:fs';
const temporary=join(tmpdir(),'priperi-push-qa');const location=join(temporary,'trigger.json');
const action=process.argv[2];
if(action==='capture') {
 const read=spawnSync('adb',['-s','emulator-5554','exec-out','run-as','com.priperi.debug','cat','files/push-lifecycle-trigger.json']);
 if(read.status!==0)throw Error('Could not read disposable test bridge');
 JSON.parse(read.stdout);mkdirSync(temporary,{mode:0o700,recursive:true});writeFileSync(location,read.stdout,{mode:0o600,flag:'wx'});
 console.log('Scoped synthetic bridge captured in protected temporary storage.');
} else if(action==='erase') {unlinkSync(location);console.log('Synthetic bridge erased.');}
else {
 if(!['0','1','2'].includes(action))throw Error('Use capture, 0, 1, 2 or erase');
 const data=JSON.parse(readFileSync(location,'utf8'));
 const response=await fetch(process.env.PRIPERI_EXPECTED_HOSTED_URL,{method:'POST',headers:{'Content-Type':'application/json',apikey:process.env.PRIPERI_HOSTED_PUBLISHABLE_KEY,Authorization:'Bearer '+data.token},body:JSON.stringify({operation:'sendMessage',deviceSecret:data.deviceSecret,message:data.messages[Number(action)]}),signal:AbortSignal.timeout(25000)});
 const body=await response.json();if(!response.ok||body.data?.state!=='sent')throw Error('Synthetic trigger rejected: HTTP '+response.status);
 console.log('Synthetic message queued:',action);
}
