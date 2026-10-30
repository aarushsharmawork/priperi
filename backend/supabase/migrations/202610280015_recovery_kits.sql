-- Apply once after014. No private recovery key or encrypted file is stored here.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
CREATE TABLE relay.recovery_kits(
 uid uuid PRIMARY KEY REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 id uuid NOT NULL UNIQUE, source_hash text NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),
 prepared_chain jsonb NOT NULL CHECK(jsonb_typeof(prepared_chain)='array' AND jsonb_array_length(prepared_chain) BETWEEN 1 AND 31 AND jsonb_array_length(prepared_chain)%2=1 AND pg_column_size(prepared_chain)<24576),
 nonce uuid, nonce_until timestamptz, target_digest text CHECK(target_digest IS NULL OR target_digest ~ '^[a-f0-9]{64}$'),
 claimed_digest text CHECK(claimed_digest IS NULL OR claimed_digest ~ '^[a-f0-9]{64}$')
);
ALTER TABLE relay.recovery_kits ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON relay.recovery_kits FROM PUBLIC,anon,authenticated,service_role;
CREATE FUNCTION public.priperi_recovery(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles;k relay.recovery_kits;target jsonb;id_value uuid;digest text;base_chain jsonb;previous_hash text;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF operation='publicAttempt' THEN
  IF NOT relay.charge('system:global','recovery_public',120,60) THEN RETURN '{"limited":true}'::jsonb;END IF;RETURN '{"ok":true}'::jsonb;
 END IF;
 IF operation IN('info','challenge','accept','close','passwordAttempt') THEN
  id_value:=(payload->>'id')::uuid;
  SELECT * INTO k FROM relay.recovery_kits WHERE id=id_value;
  IF NOT FOUND THEN RAISE EXCEPTION 'recovery_unavailable';END IF;
  IF operation='close' THEN
   DELETE FROM auth.sessions WHERE user_id=k.uid AND id=(payload->>'session')::uuid;RETURN '{"ok":true}'::jsonb;
  END IF;
  IF relay.expire_account(k.uid) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
  SELECT * INTO me FROM relay.profiles WHERE uid=k.uid;
  IF NOT FOUND OR me.auth_seed IS NULL OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=me.uid AND (completed_at IS NULL OR confirmed_at IS NULL)) THEN RAISE EXCEPTION 'recovery_unavailable';END IF;
  digest:=payload->>'digest';IF digest IS NULL OR digest !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'denied';END IF;
  SELECT COALESCE(jsonb_agg(value ORDER BY ord),'[]'::jsonb) INTO base_chain FROM jsonb_array_elements(k.prepared_chain) WITH ORDINALITY a(value,ord) WHERE ord<jsonb_array_length(k.prepared_chain);
  IF k.claimed_digest IS NULL THEN
   IF k.source_hash<>me.device_hash OR base_chain<>me.delegations THEN RAISE EXCEPTION 'recovery_unavailable';END IF;
  ELSE
   IF jsonb_array_length(me.delegations)<>jsonb_array_length(k.prepared_chain)+1 OR (SELECT jsonb_agg(value ORDER BY ord) FROM jsonb_array_elements(me.delegations) WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(k.prepared_chain))<>k.prepared_chain THEN RAISE EXCEPTION 'recovery_unavailable';END IF;
  END IF;
  IF operation='passwordAttempt' THEN IF NOT relay.charge(me.uid::text,'recovery_password',6,3600) THEN RETURN '{"limited":true}'::jsonb;END IF;RETURN '{"ok":true}'::jsonb;END IF;
  IF operation='info' THEN
   IF NOT relay.charge(me.uid::text,'recovery_lookup',30,600) THEN RETURN '{"limited":true}'::jsonb;END IF;
   RETURN jsonb_build_object('account',relay.profile(me),'chain',k.prepared_chain,'seed',me.auth_seed);
  ELSIF operation='challenge' THEN
   IF NOT relay.charge(me.uid::text,'recovery_challenge',10,600) THEN RETURN '{"limited":true}'::jsonb;END IF;
   UPDATE relay.recovery_kits SET nonce=gen_random_uuid(),nonce_until=now()+interval '2 minutes',target_digest=digest WHERE uid=me.uid RETURNING * INTO k;
   RETURN jsonb_build_object('nonce',k.nonce);
  END IF;
  IF k.nonce IS NULL OR k.nonce IS DISTINCT FROM (payload->>'nonce')::uuid OR k.nonce_until<=now() OR k.target_digest<>digest THEN RAISE EXCEPTION 'denied';END IF;
  IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=(payload->>'session')::uuid AND u.id=me.uid AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied';END IF;
  target:=payload->'target';
  IF target->>'proof' IS DISTINCT FROM proof OR proof !~ '^[a-f0-9]{64}$' OR (target->>'installation') IS NULL OR (target->>'deviceId') IS NULL OR jsonb_typeof(target->'chain')<>'array' OR jsonb_array_length(target->'chain')<>jsonb_array_length(k.prepared_chain)+1 THEN RAISE EXCEPTION 'denied';END IF;
  IF (SELECT jsonb_agg(value ORDER BY ord) FROM jsonb_array_elements(target->'chain') WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(k.prepared_chain))<>k.prepared_chain THEN RAISE EXCEPTION 'denied';END IF;
  IF k.claimed_digest=digest AND me.device_hash=proof AND me.installation=(target->>'installation')::uuid AND me.device_id=(target->>'deviceId')::uuid THEN RETURN jsonb_build_object('account',relay.profile(me));END IF;
  IF proof=me.device_hash OR (target->>'installation')::uuid=me.installation THEN RAISE EXCEPTION 'denied';END IF;
  IF EXISTS(SELECT 1 FROM relay.profiles WHERE uid<>me.uid AND (device_hash=proof OR installation=(target->>'installation')::uuid OR device_id=(target->>'deviceId')::uuid)) OR EXISTS(SELECT 1 FROM relay.pending_accounts WHERE device_hash=proof OR installation=(target->>'installation')::uuid OR device_id=(target->>'deviceId')::uuid) THEN RAISE EXCEPTION 'denied';END IF;
  previous_hash:=me.device_hash;
  UPDATE relay.profiles SET device_hash=proof,installation=(target->>'installation')::uuid,device_id=(target->>'deviceId')::uuid,delegations=target->'chain',fcm_token=NULL,active_at=now() WHERE uid=me.uid RETURNING * INTO me;
  UPDATE relay.recovery_kits SET claimed_digest=digest WHERE uid=me.uid;
  DELETE FROM auth.sessions WHERE user_id=me.uid;
  DELETE FROM relay.rates WHERE subject=previous_hash;
  RETURN jsonb_build_object('account',relay.profile(me));
 END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=session_id AND u.id=actor AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied';END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;
 IF relay.expire_account(actor) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF operation='status' THEN SELECT * INTO k FROM relay.recovery_kits WHERE uid=actor;RETURN jsonb_build_object('enabled',FOUND AND ((k.source_hash=proof AND k.claimed_digest IS NULL) OR (k.claimed_digest IS NOT NULL AND jsonb_array_length(me.delegations)=jsonb_array_length(k.prepared_chain)+1 AND (SELECT jsonb_agg(value ORDER BY ord) FROM jsonb_array_elements(me.delegations) WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(k.prepared_chain))=k.prepared_chain)));END IF;
 IF NOT recent_password OR me.auth_seed IS NULL THEN RAISE EXCEPTION 'recent_password_required';END IF;
 IF operation='revoke' THEN DELETE FROM relay.recovery_kits WHERE uid=actor;RETURN '{"ok":true}'::jsonb;END IF;
 IF operation='finish' THEN UPDATE relay.recovery_kits SET nonce=NULL,nonce_until=NULL,target_digest=NULL WHERE uid=actor AND id=(payload->>'id')::uuid AND claimed_digest IS NOT NULL;RETURN '{"ok":true}'::jsonb;END IF;
 IF operation<>'register' OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=actor) THEN RAISE EXCEPTION 'denied';END IF;
 IF jsonb_typeof(payload->'chain')<>'array' OR jsonb_array_length(payload->'chain')<>jsonb_array_length(me.delegations)+1 OR jsonb_array_length(payload->'chain')>31 THEN RAISE EXCEPTION 'denied';END IF;
 IF (SELECT COALESCE(jsonb_agg(value ORDER BY ord),'[]'::jsonb) FROM jsonb_array_elements(payload->'chain') WITH ORDINALITY a(value,ord) WHERE ord<=jsonb_array_length(me.delegations))<>me.delegations THEN RAISE EXCEPTION 'denied';END IF;
 INSERT INTO relay.recovery_kits(uid,id,source_hash,prepared_chain) VALUES(actor,(payload->>'id')::uuid,proof,payload->'chain') ON CONFLICT(uid) DO UPDATE SET id=EXCLUDED.id,source_hash=EXCLUDED.source_hash,prepared_chain=EXCLUDED.prepared_chain,nonce=NULL,nonce_until=NULL,target_digest=NULL,claimed_digest=NULL;
 RETURN '{"enabled":true}'::jsonb;
END $$;
REVOKE ALL ON FUNCTION public.priperi_recovery(uuid,uuid,text,text,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_recovery(uuid,uuid,text,text,jsonb,boolean) TO service_role;
NOTIFY pgrst,'reload schema';
COMMIT;
