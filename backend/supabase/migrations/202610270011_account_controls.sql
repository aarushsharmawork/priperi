-- Apply once after010. Opt-in controls; existing accounts default to Never.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
ALTER TABLE relay.profiles ADD COLUMN auth_seed text CHECK(auth_seed IS NULL OR auth_seed ~ '^[A-Za-z0-9_-]{43}$'), ADD COLUMN delegations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(delegations)='array' AND jsonb_array_length(delegations)<=32 AND jsonb_array_length(delegations)%2=0 AND pg_column_size(delegations)<=24576), ADD COLUMN idle_days integer NOT NULL DEFAULT 0 CHECK(idle_days IN(0,1,7,365)), ADD COLUMN active_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN move_nonce uuid, ADD COLUMN move_nonce_until timestamptz, ADD COLUMN move_lookup text;
ALTER TABLE relay.pending_accounts ADD COLUMN auth_seed text CHECK(auth_seed IS NULL OR auth_seed ~ '^[A-Za-z0-9_-]{43}$');
CREATE TABLE relay.transfers(
 uid uuid PRIMARY KEY REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 lookup_hash text NOT NULL UNIQUE CHECK(lookup_hash ~ '^[a-f0-9]{64}$'),
 claim_hash text NOT NULL CHECK(claim_hash ~ '^[a-f0-9]{64}$'),
 source_hash text NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),
 capsule text NOT NULL CHECK(length(capsule) BETWEEN 64 AND 32768),
 prepare_nonce uuid NOT NULL,
 source_installation uuid NOT NULL, source_device uuid, rollback_nonce uuid, rollback_until timestamptz,
 prepared_chain jsonb NOT NULL CHECK(jsonb_typeof(prepared_chain)='array' AND jsonb_array_length(prepared_chain) BETWEEN 1 AND 31 AND jsonb_array_length(prepared_chain)%2=1),
 expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes',
 completed_at timestamptz, confirmed_at timestamptz, receiver_digest text CHECK(receiver_digest IS NULL OR receiver_digest ~ '^[a-f0-9]{64}$')
);
ALTER TABLE relay.transfers ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON relay.transfers FROM PUBLIC,anon,authenticated;
CREATE INDEX transfer_expiry ON relay.transfers(expires_at);
CREATE INDEX profile_idle_expiry ON relay.profiles(active_at) WHERE idle_days>0;
CREATE OR REPLACE FUNCTION relay.profile(p relay.profiles) RETURNS jsonb LANGUAGE sql IMMUTABLE SET search_path='' AS $$
 SELECT jsonb_build_object('uid',p.uid,'username',p.username,'hpke',p.hpke,'signing',p.signing,'fingerprint',p.fingerprint)||CASE WHEN jsonb_array_length(p.delegations)>0 THEN jsonb_build_object('delegations',p.delegations) ELSE '{}'::jsonb END
$$;
CREATE FUNCTION relay.expire_account(actor uuid) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor AND idle_days>0 AND active_at+make_interval(days=>idle_days)<=now()) THEN
  PERFORM relay.erase_account(actor);RETURN true;
 END IF;RETURN false;
END $$;
CREATE FUNCTION public.priperi_auth_seed(actor uuid,proof text,candidate text) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE value text;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF candidate !~ '^[A-Za-z0-9_-]{43}$' THEN RAISE EXCEPTION 'denied';END IF;
 UPDATE relay.profiles SET auth_seed=COALESCE(auth_seed,candidate) WHERE uid=actor AND device_hash=proof RETURNING auth_seed INTO value;
 IF FOUND THEN RETURN value;END IF;
 UPDATE relay.pending_accounts SET auth_seed=COALESCE(auth_seed,candidate) WHERE uid=actor AND device_hash=proof RETURNING auth_seed INTO value;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;RETURN value;
END $$;
CREATE FUNCTION public.priperi_account(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; days integer;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=session_id AND u.id=actor AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied';END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;
 IF relay.expire_account(actor) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF NOT relay.charge(actor::text,'account',30,60) THEN RETURN '{"limited":true}'::jsonb;END IF;
 IF operation='presence' THEN
  UPDATE relay.profiles SET active_at=now() WHERE uid=actor AND active_at<now()-interval '5 minutes';
 ELSIF operation='setPolicy' THEN
  IF NOT recent_password THEN RAISE EXCEPTION 'recent_password_required';END IF;
  days:=(payload->>'days')::integer;
  IF days IS NULL OR days NOT IN(0,1,7,365) THEN RAISE EXCEPTION 'denied';END IF;
  UPDATE relay.profiles SET idle_days=days,active_at=now() WHERE uid=actor;
 ELSIF operation='cancelTransfers' THEN DELETE FROM relay.transfers WHERE uid=actor AND completed_at IS NULL;
 ELSIF operation<>'getPolicy' THEN RAISE EXCEPTION 'invalid_operation';END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor;
 RETURN jsonb_build_object('days',me.idle_days,'lastActive',floor(extract(epoch FROM me.active_at)*1000),'serverTime',floor(extract(epoch FROM now())*1000));
END $$;
CREATE FUNCTION public.priperi_transfer(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles;t relay.transfers;lookup text;target jsonb;target_hash text;target_installation uuid;target_device uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 lookup:=payload->>'lookup';
 IF lookup IS NULL OR lookup !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'denied';END IF;
 IF operation='publicAttempt' THEN IF NOT relay.charge('system:global','transfer_public',180,60) THEN RETURN '{"limited":true}'::jsonb;END IF;RETURN '{"ok":true}'::jsonb;END IF;
 IF operation IN('read','accept','sourceStatus','sourceAck','claimInfo','sourceChallenge','rollbackInfo','rollback') THEN
  SELECT * INTO t FROM relay.transfers WHERE lookup_hash=lookup;
  IF NOT FOUND OR t.expires_at<=now() AND t.completed_at IS NULL THEN RAISE EXCEPTION 'transfer_unavailable';END IF;
  IF relay.expire_account(t.uid) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
  IF operation IN('sourceChallenge','rollbackInfo','rollback') THEN
   IF t.source_hash<>proof OR t.completed_at IS NULL OR t.confirmed_at IS NOT NULL THEN RAISE EXCEPTION 'denied';END IF;
   IF NOT relay.charge(lookup,'rollback',12,60) THEN RETURN '{"limited":true}'::jsonb;END IF;
   SELECT * INTO me FROM relay.profiles WHERE uid=t.uid;
   IF operation='sourceChallenge' THEN UPDATE relay.transfers SET rollback_nonce=gen_random_uuid(),rollback_until=now()+interval '2 minutes' WHERE uid=t.uid RETURNING * INTO t;RETURN jsonb_build_object('nonce',t.rollback_nonce);END IF;
   IF t.rollback_nonce IS NULL OR t.rollback_nonce IS DISTINCT FROM (payload->>'nonce')::uuid OR t.rollback_until<=now() THEN RAISE EXCEPTION 'denied';END IF;
   IF operation='rollbackInfo' THEN RETURN jsonb_build_object('uid',me.uid,'fingerprint',me.fingerprint,'authSeed',me.auth_seed,'sourceKey',CASE WHEN jsonb_array_length(t.prepared_chain)=1 THEN me.signing ELSE t.prepared_chain->(jsonb_array_length(t.prepared_chain)-2)->>'key' END);END IF;
   IF EXISTS(SELECT 1 FROM relay.profiles WHERE uid<>me.uid AND (device_hash=t.source_hash OR installation=t.source_installation OR (t.source_device IS NOT NULL AND device_id=t.source_device))) OR EXISTS(SELECT 1 FROM relay.pending_accounts WHERE device_hash=t.source_hash OR installation=t.source_installation OR (t.source_device IS NOT NULL AND device_id=t.source_device)) THEN RAISE EXCEPTION 'denied';END IF;
   UPDATE relay.profiles SET device_hash=t.source_hash,installation=t.source_installation,device_id=t.source_device,delegations=(SELECT COALESCE(jsonb_agg(value ORDER BY ord),'[]'::jsonb) FROM jsonb_array_elements(t.prepared_chain) WITH ORDINALITY a(value,ord) WHERE ord<jsonb_array_length(t.prepared_chain)),fcm_token=NULL,active_at=now() WHERE uid=me.uid;
   DELETE FROM auth.sessions WHERE user_id=me.uid;
   DELETE FROM relay.rates WHERE subject=t.lookup_hash;DELETE FROM relay.transfers WHERE uid=me.uid;
   RETURN '{"rolledBack":true}'::jsonb;
  END IF;
  IF operation IN('sourceStatus','sourceAck') THEN
   IF t.source_hash<>proof THEN RAISE EXCEPTION 'denied';END IF;
   IF NOT relay.charge(lookup,'transfer_status',12,60) THEN RETURN '{"limited":true}'::jsonb;END IF;
   IF operation='sourceAck' THEN IF t.confirmed_at IS NULL THEN RAISE EXCEPTION 'denied';END IF;DELETE FROM relay.rates WHERE subject=t.lookup_hash;DELETE FROM relay.transfers WHERE uid=t.uid;RETURN '{"ok":true}'::jsonb;END IF;
   RETURN jsonb_build_object('uid',t.uid,'complete',t.confirmed_at IS NOT NULL,'moved',t.completed_at IS NOT NULL);
  END IF;
  IF NOT relay.charge(lookup,'transfer',30,60) THEN RETURN '{"limited":true}'::jsonb;END IF;
  SELECT * INTO me FROM relay.profiles WHERE uid=t.uid;
  IF NOT FOUND THEN RAISE EXCEPTION 'transfer_unavailable';END IF;
  IF relay.expire_account(me.uid) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
  IF operation='read' THEN
   IF t.completed_at IS NOT NULL THEN RAISE EXCEPTION 'transfer_unavailable';END IF;
   RETURN jsonb_build_object('account',relay.profile(me),'capsule',t.capsule,'chain',t.prepared_chain,'expiresAt',floor(extract(epoch FROM t.expires_at)*1000));
  END IF;
  IF payload->>'claimHash' IS NULL OR payload->>'claimHash'<>t.claim_hash THEN RAISE EXCEPTION 'denied';END IF;
  IF operation='claimInfo' THEN RETURN jsonb_build_object('account',relay.profile(me),'chain',t.prepared_chain);END IF;
  target:=payload->'target';target_hash:=target->>'proof';target_installation:=(target->>'installation')::uuid;target_device:=(target->>'deviceId')::uuid;
  IF target_hash IS NULL OR target_hash !~ '^[a-f0-9]{64}$' OR target_installation IS NULL OR target_device IS NULL OR (payload->>'digest') IS NULL OR payload->>'digest' !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'denied';END IF;
  IF t.completed_at IS NOT NULL THEN
   IF t.receiver_digest<>payload->>'digest' OR me.device_hash<>target_hash OR me.installation<>target_installation OR me.device_id<>target_device THEN RAISE EXCEPTION 'denied';END IF;
   RETURN jsonb_build_object('account',relay.profile(me),'complete',true);
  END IF;
  IF me.device_hash<>t.source_hash OR me.auth_seed IS NULL OR target_hash=me.device_hash OR target_installation=me.installation OR target_device=me.device_id THEN RAISE EXCEPTION 'denied';END IF;
  IF jsonb_array_length(t.prepared_chain)<>jsonb_array_length(me.delegations)+1 OR t.prepared_chain->(jsonb_array_length(t.prepared_chain)-1) IS NULL THEN RAISE EXCEPTION 'denied';END IF;
  IF (SELECT COALESCE(jsonb_agg(value ORDER BY ord),'[]'::jsonb) FROM jsonb_array_elements(t.prepared_chain) WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(me.delegations))<>me.delegations THEN RAISE EXCEPTION 'denied';END IF;
  IF jsonb_typeof(target->'chain')<>'array' OR jsonb_array_length(target->'chain')<>jsonb_array_length(t.prepared_chain)+1 OR (SELECT jsonb_agg(value ORDER BY ord) FROM jsonb_array_elements(target->'chain') WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(t.prepared_chain))<>t.prepared_chain THEN RAISE EXCEPTION 'denied';END IF;
  IF EXISTS(SELECT 1 FROM relay.profiles WHERE uid<>me.uid AND (device_hash=target_hash OR installation=target_installation OR device_id=target_device)) OR EXISTS(SELECT 1 FROM relay.pending_accounts WHERE device_hash=target_hash OR installation=target_installation OR device_id=target_device) THEN RAISE EXCEPTION 'denied';END IF;
  UPDATE relay.profiles SET device_hash=target_hash,installation=target_installation,device_id=target_device,delegations=target->'chain',fcm_token=NULL,active_at=now() WHERE uid=me.uid RETURNING * INTO me;
  DELETE FROM auth.sessions WHERE user_id=me.uid;
  UPDATE relay.transfers SET completed_at=now(),receiver_digest=payload->>'digest',capsule=repeat('0',64),claim_hash=t.claim_hash WHERE uid=me.uid;
  -- Only the 256-bit lookup counter remains until bounded sweep; all former device counters go.
  DELETE FROM relay.rates WHERE subject=t.source_hash;
  RETURN jsonb_build_object('account',relay.profile(me),'complete',true);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=session_id AND u.id=actor AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied';END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;
 IF relay.expire_account(actor) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF operation='challenge' THEN
  IF NOT recent_password OR me.auth_seed IS NULL THEN RAISE EXCEPTION 'recent_password_required';END IF;
  IF EXISTS(SELECT 1 FROM relay.transfers WHERE uid=actor AND completed_at IS NOT NULL AND confirmed_at IS NULL) THEN RAISE EXCEPTION 'finish_transfer_first';END IF;
  UPDATE relay.profiles SET move_nonce=gen_random_uuid(),move_nonce_until=now()+interval '2 minutes',move_lookup=lookup WHERE uid=actor RETURNING * INTO me;
  RETURN jsonb_build_object('nonce',me.move_nonce);
 ELSIF operation='confirm' THEN
  UPDATE relay.transfers SET confirmed_at=COALESCE(confirmed_at,now()) WHERE uid=actor AND lookup_hash=lookup AND completed_at IS NOT NULL;
  IF NOT FOUND AND EXISTS(SELECT 1 FROM relay.transfers WHERE uid=actor) THEN RAISE EXCEPTION 'transfer_unavailable';END IF;RETURN '{"ok":true}'::jsonb;
 ELSIF operation='prepare' THEN
  IF NOT recent_password OR me.auth_seed IS NULL THEN RAISE EXCEPTION 'recent_password_required';END IF;
  IF (payload->>'claimHash') IS NULL OR payload->>'claimHash' !~ '^[a-f0-9]{64}$' OR length(payload->>'capsule') NOT BETWEEN 64 AND 32768 OR jsonb_typeof(payload->'chain')<>'array' OR jsonb_array_length(payload->'chain')<>jsonb_array_length(me.delegations)+1 OR jsonb_array_length(payload->'chain')>31 THEN RAISE EXCEPTION 'denied';END IF;
  IF (SELECT COALESCE(jsonb_agg(value ORDER BY ord),'[]'::jsonb) FROM jsonb_array_elements(payload->'chain') WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(me.delegations))<>me.delegations THEN RAISE EXCEPTION 'denied';END IF;
  SELECT * INTO t FROM relay.transfers WHERE uid=actor;
  IF FOUND AND t.lookup_hash=lookup AND t.prepare_nonce=(payload->>'nonce')::uuid AND t.claim_hash=payload->>'claimHash' AND t.capsule=payload->>'capsule' AND t.prepared_chain=payload->'chain' AND t.completed_at IS NULL AND t.expires_at>now() THEN RETURN jsonb_build_object('expiresAt',floor(extract(epoch FROM t.expires_at)*1000));END IF;
  IF me.move_nonce IS NULL OR me.move_nonce IS DISTINCT FROM (payload->>'nonce')::uuid OR me.move_nonce_until<=now() OR me.move_lookup IS DISTINCT FROM lookup THEN RAISE EXCEPTION 'denied';END IF;
  UPDATE relay.profiles SET move_nonce=NULL,move_nonce_until=NULL,move_lookup=NULL WHERE uid=actor;
  INSERT INTO relay.transfers(uid,lookup_hash,claim_hash,source_hash,capsule,prepare_nonce,source_installation,source_device,prepared_chain) VALUES(actor,lookup,payload->>'claimHash',proof,payload->>'capsule',(payload->>'nonce')::uuid,me.installation,me.device_id,payload->'chain')
   ON CONFLICT(uid) DO UPDATE SET lookup_hash=EXCLUDED.lookup_hash,claim_hash=EXCLUDED.claim_hash,source_hash=EXCLUDED.source_hash,capsule=EXCLUDED.capsule,prepare_nonce=EXCLUDED.prepare_nonce,source_installation=EXCLUDED.source_installation,source_device=EXCLUDED.source_device,rollback_nonce=NULL,rollback_until=NULL,prepared_chain=EXCLUDED.prepared_chain,expires_at=now()+interval '15 minutes',completed_at=NULL,confirmed_at=NULL,receiver_digest=NULL;
  RETURN jsonb_build_object('expiresAt',floor(extract(epoch FROM (now()+interval '15 minutes'))*1000));
 ELSIF operation='cancel' THEN
  UPDATE relay.profiles SET move_nonce=NULL,move_nonce_until=NULL,move_lookup=NULL WHERE uid=actor AND move_lookup=lookup;
  DELETE FROM relay.transfers WHERE uid=actor AND lookup_hash=lookup AND completed_at IS NULL;
  RETURN '{"ok":true}'::jsonb;
 END IF;
 RAISE EXCEPTION 'invalid_operation';
END $$;
CREATE OR REPLACE FUNCTION public.priperi_begin_device(name text,proof text,installation_id uuid,hpke_key text,signing_key text,fingerprint_value text,expected_actor uuid,device_id_value uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; me relay.profiles; pending relay.pending_accounts;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 SELECT * INTO me FROM relay.profiles WHERE device_hash=proof AND installation=installation_id AND fingerprint=fingerprint_value;
 IF FOUND AND relay.expire_account(me.uid) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF NOT relay.charge('system:global','device_login',180,60) THEN RETURN '{"limited":true}'::jsonb; END IF;
 IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value
  AND (device_hash<>proof OR installation<>installation_id OR fingerprint<>fingerprint_value)) THEN RETURN '{"denied":true}'::jsonb; END IF;
 SELECT * INTO me FROM relay.profiles WHERE device_hash=proof OR installation=installation_id OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
 IF FOUND THEN
  IF me.device_hash<>proof OR me.installation<>installation_id OR me.fingerprint<>fingerprint_value
   OR (me.device_id IS NOT NULL AND me.device_id IS DISTINCT FROM device_id_value)
   OR (expected_actor IS NOT NULL AND expected_actor<>me.uid) THEN
   RETURN '{"denied":true}'::jsonb; END IF;
  -- Username is discovery-only. The device resolves the real current account.
  name:=me.username;
 ELSE
  IF name IS NULL OR expected_actor IS NOT NULL THEN
   RETURN '{"denied":true}'::jsonb; END IF;
  SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof OR installation=installation_id OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
  IF FOUND THEN
   IF pending.device_hash<>proof OR pending.installation<>installation_id OR pending.fingerprint<>fingerprint_value
    OR (pending.device_id IS NOT NULL AND pending.device_id IS DISTINCT FROM device_id_value) THEN RETURN '{"denied":true}'::jsonb; END IF;
   name:=pending.username;
  END IF;
 END IF;
 result:=public.priperi_begin(name,proof,installation_id,hpke_key,signing_key,fingerprint_value,expected_actor);
 IF COALESCE((result->>'pending')::boolean,false) THEN
  UPDATE relay.pending_accounts SET device_id=device_id_value WHERE uid=(result->>'uid')::uuid AND (device_id IS NULL OR device_id IS NOT DISTINCT FROM device_id_value);
  IF NOT FOUND THEN RETURN '{"denied":true}'::jsonb; END IF;
 END IF;
 RETURN result;
END $$;
CREATE OR REPLACE FUNCTION public.priperi_device(proof text,installation_id uuid,fingerprint_value text,device_id_value uuid,action text,payload jsonb DEFAULT '{}'::jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; pending relay.pending_accounts; challenge relay.reset_challenges; actor uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 SELECT * INTO me FROM relay.profiles WHERE device_hash=proof AND installation=installation_id AND fingerprint=fingerprint_value;
 IF FOUND AND relay.expire_account(me.uid) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF action='resetAccount' AND me.uid IS NOT NULL AND COALESCE(payload->'delegations','[]'::jsonb)<>me.delegations THEN RETURN '{"denied":true}'::jsonb;END IF;
 IF action NOT IN ('status','resetChallenge','resetAccount','bind') THEN RAISE EXCEPTION 'denied'; END IF;
 -- A retry after confirmed reset can complete without retaining a tombstone.
 IF action='resetAccount' THEN
  actor:=(payload->>'uid')::uuid;
  IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor) AND NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor)
   AND NOT EXISTS(SELECT 1 FROM relay.profiles WHERE installation=installation_id OR device_hash=proof OR (device_id_value IS NOT NULL AND device_id=device_id_value))
   THEN RETURN '{"status":"deleted"}'::jsonb; END IF;
 END IF;
 IF action<>'bind' AND NOT relay.charge('system:global','device',180,60) THEN RETURN '{"limited":true}'::jsonb; END IF;
 IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value
  AND (device_hash<>proof OR installation<>installation_id OR fingerprint<>fingerprint_value)) THEN RETURN '{"denied":true}'::jsonb; END IF;
 SELECT * INTO me FROM relay.profiles WHERE installation=installation_id OR device_hash=proof OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
 IF FOUND THEN
  IF me.installation<>installation_id OR me.device_hash<>proof OR me.fingerprint<>fingerprint_value
   OR (me.device_id IS NOT NULL AND me.device_id IS DISTINCT FROM device_id_value) THEN RETURN '{"denied":true}'::jsonb; END IF;
  IF action<>'bind' AND NOT relay.charge(proof,CASE WHEN action='status' THEN 'device_status' ELSE 'device_reset' END,
   CASE WHEN action='status' THEN 30 ELSE 8 END,CASE WHEN action='status' THEN 60 ELSE 600 END)
   THEN RETURN '{"limited":true}'::jsonb; END IF;
  IF action='status' THEN RETURN jsonb_build_object('registered',true,'uid',me.uid,'username',me.username); END IF;
  IF action='bind' THEN
   IF device_id_value IS NULL OR me.uid<>(payload->>'uid')::uuid THEN RAISE EXCEPTION 'denied'; END IF;
   IF EXISTS(SELECT 1 FROM relay.pending_accounts WHERE device_id=device_id_value AND uid<>me.uid) THEN RAISE EXCEPTION 'denied'; END IF;
   UPDATE relay.profiles SET device_id=device_id_value WHERE uid=me.uid;
   RETURN '{"ok":true}'::jsonb;
  ELSIF action='resetChallenge' THEN
   INSERT INTO relay.reset_challenges(uid,nonce,expires_at) VALUES(me.uid,gen_random_uuid(),now()+interval '5 minutes')
    ON CONFLICT(uid) DO UPDATE SET nonce=EXCLUDED.nonce,expires_at=EXCLUDED.expires_at RETURNING * INTO challenge;
   RETURN jsonb_build_object('uid',me.uid,'nonce',challenge.nonce);
  ELSIF action='resetAccount' THEN
   IF actor<>me.uid THEN RAISE EXCEPTION 'denied'; END IF;
   SELECT * INTO challenge FROM relay.reset_challenges WHERE uid=me.uid AND nonce=(payload->>'nonce')::uuid AND expires_at>now();
   IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
   -- Auth FK cascades remove sessions, queued messages, receipts, blocks and challenge.
   PERFORM relay.erase_account(me.uid);
   RETURN '{"status":"deleted"}'::jsonb;
  END IF;
 END IF;
 IF action<>'status' THEN RETURN '{"denied":true}'::jsonb; END IF;
 SELECT * INTO pending FROM relay.pending_accounts WHERE installation=installation_id OR device_hash=proof OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
 IF FOUND THEN
  IF pending.installation<>installation_id OR pending.device_hash<>proof OR pending.fingerprint<>fingerprint_value
   OR (pending.device_id IS NOT NULL AND pending.device_id IS DISTINCT FROM device_id_value) THEN RETURN '{"denied":true}'::jsonb; END IF;
  IF NOT relay.charge(proof,'device_status',30,60) THEN RETURN '{"limited":true}'::jsonb; END IF;
  RETURN jsonb_build_object('registered',false,'pendingUsername',pending.username);
 END IF;
 RETURN '{"registered":false}'::jsonb;
END $$;
CREATE OR REPLACE FUNCTION public.priperi_finish(actor uuid,proof text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE p relay.profiles;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor AND device_hash=proof) AND relay.expire_account(actor) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation,device_id)
 SELECT uid,username,hpke,signing,fingerprint,device_hash,installation,device_id FROM relay.pending_accounts WHERE uid=actor AND device_hash=proof
 ON CONFLICT(uid) DO NOTHING;
 SELECT * INTO p FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 UPDATE relay.profiles current_profile SET auth_seed=COALESCE(current_profile.auth_seed,a.auth_seed) FROM relay.pending_accounts a WHERE current_profile.uid=actor AND a.uid=actor AND a.device_hash=proof;
 DELETE FROM relay.pending_accounts WHERE uid=actor;
 RETURN relay.profile(p);
END $$;
CREATE OR REPLACE FUNCTION public.priperi_request(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; peer relay.profiles; m jsonb; item relay.messages; receipt relay.receipts; peer_id uuid; message_id uuid; output jsonb; pending_id uuid; permission relay.contact_permissions; message_expiry timestamptz;
BEGIN
 -- One brief transaction serializes relay writes, including deletion, send and block.
 PERFORM pg_advisory_xact_lock(774392);
 -- Lost deletion responses can be retried with the already verified, recent
 -- password JWT. No identifier, tombstone or secret needs to remain afterward.
 IF operation='deleteAccount' AND recent_password AND NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor)
  AND NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor) THEN RETURN '{"ok":true,"status":"deleted"}'::jsonb; END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=session_id AND u.id=actor
  AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied'; END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 IF relay.expire_account(actor) THEN IF operation='deleteAccount' THEN RETURN '{"ok":true,"status":"deleted"}'::jsonb;END IF;RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF operation='getAccount' THEN RETURN relay.profile(me);
 ELSIF operation='lookupUser' THEN
  SELECT * INTO peer FROM relay.profiles WHERE uid<>actor AND
   ((payload ? 'username' AND username=payload->>'username') OR (NOT payload ? 'username' AND uid=(payload->>'uid')::uuid));
  IF NOT FOUND OR EXISTS(SELECT 1 FROM relay.blocked WHERE owner=peer.uid AND relay.blocked.target=actor) THEN RAISE EXCEPTION 'not_found'; END IF;
  RETURN relay.profile(peer)||jsonb_build_object('contactState',relay.contact_state(actor,peer.uid));
 ELSIF operation='approveContact' THEN
  peer_id:=(payload->>'uid')::uuid;
  IF peer_id=actor OR NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=peer_id)
   OR EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=peer_id) OR (owner=peer_id AND target=actor)) THEN RAISE EXCEPTION 'not_found'; END IF;
  UPDATE relay.contact_permissions SET approved=true WHERE sender=peer_id AND recipient=actor AND sent_count>0;
  IF NOT FOUND THEN RAISE EXCEPTION 'no_contact_request'; END IF;
  RETURN relay.contact_state(actor,peer_id);
 ELSIF operation='renameAccount' THEN
  IF EXISTS(SELECT 1 FROM relay.pending_accounts WHERE username=payload->>'username') THEN RAISE EXCEPTION 'name_taken'; END IF;
  IF me.username<>payload->>'username' THEN DELETE FROM relay.rates WHERE subject=me.username; END IF;
  UPDATE relay.profiles SET username=payload->>'username' WHERE uid=actor; RETURN jsonb_build_object('username',payload->>'username');
 ELSIF operation='setNotificationToken' THEN
  UPDATE relay.profiles SET fcm_token=payload->>'token' WHERE uid=actor; RETURN '{"ok":true}'::jsonb;
 ELSIF operation='setBlocked' THEN
  peer_id:=(payload->>'uid')::uuid;
  IF peer_id=actor THEN RAISE EXCEPTION 'denied'; END IF;
  IF (payload->>'blocked')::boolean THEN
   INSERT INTO relay.blocked(owner,target) VALUES(actor,peer_id) ON CONFLICT DO NOTHING;
   DELETE FROM relay.messages WHERE (sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor);
  ELSE DELETE FROM relay.blocked WHERE owner=actor AND relay.blocked.target=peer_id; END IF;
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='sendMessage' THEN
  m:=payload->'message'; peer_id:=(m->>'recipient')::uuid; message_id:=(m->>'id')::uuid;
  SELECT * INTO peer FROM relay.profiles WHERE uid=peer_id;
  IF NOT FOUND OR peer_id=actor OR (m->>'sender')::uuid<>actor OR m->>'senderFingerprint'<>me.fingerprint OR m->>'recipientFingerprint'<>peer.fingerprint
   OR EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND relay.blocked.target=peer_id) OR (owner=peer_id AND relay.blocked.target=actor)) THEN RAISE EXCEPTION 'denied'; END IF;
  SELECT * INTO receipt FROM relay.receipts WHERE id=message_id;
  IF FOUND THEN
   IF receipt.sender<>actor OR receipt.recipient<>peer_id OR receipt.digest<>payload->>'digest' THEN RAISE EXCEPTION 'conflict'; END IF;
   RETURN jsonb_build_object('state','delivered','contactState',relay.contact_state(actor,peer_id));
  END IF;
  SELECT * INTO item FROM relay.messages WHERE id=message_id;
  IF FOUND THEN
   IF item.sender<>actor OR item.recipient<>peer_id OR item.digest<>payload->>'digest' THEN RAISE EXCEPTION 'conflict'; END IF;
   RETURN jsonb_build_object('state','sent','contactState',relay.contact_state(actor,peer_id));
  END IF;
  -- Validate again at the transaction boundary, after Edge verification/network delay.
  -- Exact already-accepted retries above retain their original idempotent result.
  message_expiry:=to_timestamp(((m->>'sentAt')::bigint+604800000)/1000.0);
  IF message_expiry IS NULL OR message_expiry<=now() OR message_expiry>now()+interval '7 days 1 minute' THEN RAISE EXCEPTION 'denied'; END IF;
  SELECT * INTO permission FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id;
  IF FOUND AND NOT permission.approved AND permission.sent_count>=3 THEN RAISE EXCEPTION 'contact_approval_required'; END IF;
  IF (SELECT count(*) FROM relay.messages WHERE recipient=peer_id)>=500 THEN RAISE EXCEPTION 'inbox_full'; END IF;
  INSERT INTO relay.messages(id,sender,recipient,envelope,digest,expires_at)
   VALUES(message_id,actor,peer_id,m,payload->>'digest',message_expiry);
  INSERT INTO relay.contact_permissions AS c(sender,recipient,sent_count) VALUES(actor,peer_id,1)
   ON CONFLICT(sender,recipient) DO UPDATE SET sent_count=least(c.sent_count+1,3);
  RETURN jsonb_build_object('state','sent','notify',peer.fcm_token,'contactState',relay.contact_state(actor,peer_id));
 ELSIF operation='syncInbox' THEN
  SELECT jsonb_build_object('messages',COALESCE((SELECT jsonb_agg(x.envelope) FROM
   (SELECT envelope FROM relay.messages WHERE recipient=actor AND expires_at>now() ORDER BY expires_at,id LIMIT 50) x),'[]'::jsonb),
   'receipts',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',id,'recipient',recipient)) FROM relay.receipts
   WHERE sender=actor AND expires_at>now() AND id::text IN (SELECT jsonb_array_elements_text(payload->'receiptIds'))),'[]'::jsonb)) INTO output;
  RETURN output||jsonb_build_object('contactStates',COALESCE((SELECT jsonb_agg(relay.contact_state(actor,x.uid)) FROM
   (SELECT value::uuid AS uid FROM jsonb_array_elements_text(COALESCE(payload->'contactIds','[]'::jsonb))
    UNION SELECT (value->>'sender')::uuid FROM jsonb_array_elements(output->'messages')) x),'[]'::jsonb));
 ELSIF operation='acknowledgeMessage' THEN
  message_id:=(payload->>'id')::uuid; SELECT * INTO item FROM relay.messages WHERE id=message_id;
  IF FOUND THEN
   IF item.recipient<>actor THEN RAISE EXCEPTION 'denied'; END IF;
   INSERT INTO relay.receipts(id,sender,recipient,digest,expires_at) VALUES(item.id,item.sender,item.recipient,item.digest,item.expires_at) ON CONFLICT DO NOTHING;
   DELETE FROM relay.messages WHERE id=message_id;
  END IF;
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='deleteAccount' THEN
  IF NOT recent_password THEN RAISE EXCEPTION 'recent_password_required'; END IF;
  -- Auth, sessions, profile, both sides' queued messages, receipts and block
  -- references disappear atomically through foreign-key cascades.
  PERFORM relay.erase_account(actor);
  RETURN '{"ok":true,"status":"deleted"}'::jsonb;
 END IF;
 RAISE EXCEPTION 'invalid_operation';
END $$;
CREATE OR REPLACE FUNCTION public.priperi_attempt(actor uuid,session_id uuid,proof text,kind text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE cap integer;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id
  JOIN relay.profiles p ON p.uid=u.id WHERE s.id=session_id AND u.id=actor AND p.device_hash=proof
  AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RETURN false; END IF;
 IF relay.expire_account(actor) THEN RETURN false;END IF;
 cap:=CASE kind WHEN 'lookup' THEN 20 WHEN 'send' THEN 30 WHEN 'sync' THEN 30 WHEN 'ack' THEN 120 WHEN 'settings' THEN 20 ELSE 180 END;
 RETURN relay.charge(actor::text,'attempts',180,60) AND relay.charge(actor::text,kind,cap,60);
END $$;
CREATE OR REPLACE FUNCTION relay.erase_account(actor uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; owned uuid[];
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 SELECT * INTO me FROM relay.profiles WHERE uid=actor;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 SELECT array_agg(p.uid) INTO owned FROM relay.pending_accounts p WHERE
  (p.device_hash=me.device_hash OR (p.installation=me.installation AND p.fingerprint=me.fingerprint)
   OR (me.device_id IS NOT NULL AND p.device_id=me.device_id AND p.fingerprint=me.fingerprint))
  AND NOT EXISTS(SELECT 1 FROM relay.profiles other WHERE other.uid=p.uid);
 DELETE FROM relay.rates WHERE subject IN(SELECT lookup_hash FROM relay.transfers WHERE uid=actor);
 DELETE FROM relay.rates WHERE subject IN(actor::text,me.device_hash)
  OR (kind='login' AND (subject=me.username
   OR subject IN(SELECT p.username FROM relay.pending_accounts p WHERE p.uid=ANY(owned))))
  OR subject IN(SELECT unnest(owned)::text);
 DELETE FROM relay.pending_accounts WHERE uid=actor OR uid=ANY(owned);
 DELETE FROM auth.users WHERE id=ANY(owned);
 DELETE FROM auth.users WHERE id=actor;
END $$;
ALTER FUNCTION relay.sweep() RENAME TO sweep_v271;
CREATE FUNCTION relay.sweep() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 PERFORM relay.sweep_v271();
 UPDATE relay.profiles SET move_nonce=NULL,move_nonce_until=NULL,move_lookup=NULL WHERE move_nonce_until<=now();
 FOR actor IN SELECT uid FROM relay.profiles WHERE idle_days>0 AND active_at+make_interval(days=>idle_days)<=now() LOOP PERFORM relay.erase_account(actor);END LOOP;
 DELETE FROM relay.transfers WHERE completed_at IS NULL AND expires_at<=now();
END $$;
REVOKE ALL ON FUNCTION relay.expire_account(uuid),relay.sweep(),relay.sweep_v271() FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.priperi_auth_seed(uuid,text,text),public.priperi_account(uuid,uuid,text,text,jsonb,boolean),public.priperi_transfer(uuid,uuid,text,text,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_auth_seed(uuid,text,text),public.priperi_account(uuid,uuid,text,text,jsonb,boolean),public.priperi_transfer(uuid,uuid,text,text,jsonb,boolean) TO service_role;
NOTIFY pgrst,'reload schema';
COMMIT;
