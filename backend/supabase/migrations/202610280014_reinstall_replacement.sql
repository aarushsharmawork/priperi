-- Apply once after013. Existing accounts are unchanged until their owner
-- authorises a replacement with the old password and finishes a new signup.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
DO $guard$
BEGIN
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid='public.priperi_begin_device(text,text,uuid,text,text,text,uuid,uuid)'::regprocedure) IS DISTINCT FROM '04e30e794ee454a741b322310fb21d6b'
 OR (SELECT md5(prosrc) FROM pg_proc WHERE oid='public.priperi_device(text,uuid,text,uuid,text,jsonb)'::regprocedure) IS DISTINCT FROM '4a7f6c782d3051c7b3dfd7a817da97b4'
 OR (SELECT md5(prosrc) FROM pg_proc WHERE oid='public.priperi_finish(uuid,text)'::regprocedure) IS DISTINCT FROM '2f36f94fa5e140689335fba8edf79253'
 OR (SELECT md5(prosrc) FROM pg_proc WHERE oid='relay.erase_account(uuid)'::regprocedure) IS DISTINCT FROM '41755eed3f0c0c146a6055cfb5f4e7c4' THEN RAISE EXCEPTION 'Unexpected prior definition; review installed migrations before014';END IF;
END $guard$;
ALTER TABLE relay.pending_accounts
 ADD COLUMN replaces_uid uuid REFERENCES relay.profiles(uid) ON DELETE SET NULL,
 ADD COLUMN replaces_hash text CHECK(replaces_hash IS NULL OR replaces_hash ~ '^[a-f0-9]{64}$'),
 ADD COLUMN replacement_until timestamptz,
 ADD CONSTRAINT complete_replacement CHECK(replaces_uid IS NULL OR (replaces_hash IS NOT NULL AND replacement_until IS NOT NULL));

-- Service-only password-verification context. Every attempt is committed before
-- the Edge checks a password; changing the supplied new proof cannot reset it.
CREATE FUNCTION public.priperi_replacement(proof text,installation_id uuid,device_id_value uuid,fingerprint_value text,action text,payload jsonb DEFAULT '{}'::jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE old relay.profiles; pending relay.pending_accounts; result jsonb;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF proof IS NULL OR proof !~ '^[a-f0-9]{64}$' OR fingerprint_value IS NULL OR fingerprint_value !~ '^[a-f0-9]{64}$' OR installation_id IS NULL OR device_id_value IS NULL THEN RAISE EXCEPTION 'denied';END IF;
 IF action='close' THEN
  DELETE FROM auth.sessions WHERE id=(payload->>'session')::uuid AND user_id=(payload->>'uid')::uuid;
  RETURN '{"ok":true}'::jsonb;
 END IF;
 IF action='info' THEN
  IF NOT relay.charge('system:global','replacement',30,600) OR NOT relay.charge(device_id_value::text,'replacement',5,3600) THEN RETURN '{"limited":true}'::jsonb;END IF;
 ELSIF action<>'prepare' THEN RAISE EXCEPTION 'denied';END IF;
 SELECT * INTO old FROM relay.profiles WHERE device_id=device_id_value;
 IF NOT FOUND OR old.device_hash=proof OR old.installation=installation_id OR old.fingerprint=fingerprint_value OR old.auth_seed IS NULL
  OR EXISTS(SELECT 1 FROM relay.profiles WHERE device_hash=proof OR installation=installation_id)
  OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=old.uid AND ((completed_at IS NULL AND expires_at>now()) OR (completed_at IS NOT NULL AND confirmed_at IS NULL))) THEN RETURN '{"denied":true}'::jsonb;END IF;
 IF action='info' THEN RETURN jsonb_build_object('uid',old.uid,'seed',old.auth_seed);END IF;
 -- The Edge verifies this fresh password JWT's signature/issuer/audience/version.
 -- SQL independently checks its live session and the exact old binding again.
 IF old.uid IS DISTINCT FROM (payload->>'uid')::uuid OR old.auth_seed IS DISTINCT FROM payload->>'seed'
  OR NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=(payload->>'session')::uuid AND s.user_id=old.uid AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied';END IF;
 IF EXISTS(SELECT 1 FROM relay.profiles WHERE username=payload->>'username') OR EXISTS(SELECT 1 FROM relay.pending_accounts WHERE username=payload->>'username' AND (device_hash<>proof OR installation<>installation_id)) THEN RETURN '{"nameTaken":true}'::jsonb;END IF;
 SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof OR installation=installation_id OR device_id=device_id_value LIMIT 1;
 IF FOUND AND (pending.device_hash<>proof OR pending.installation<>installation_id OR pending.fingerprint<>fingerprint_value OR pending.username<>payload->>'username') THEN RETURN '{"denied":true}'::jsonb;END IF;
 result:=public.priperi_begin(payload->>'username',proof,installation_id,payload->>'hpke',payload->>'signing',fingerprint_value,NULL);
 IF COALESCE((result->>'pending')::boolean,false) THEN
  UPDATE relay.pending_accounts SET device_id=device_id_value,replaces_uid=old.uid,replaces_hash=old.device_hash,replacement_until=now()+interval '5 minutes' WHERE uid=(result->>'uid')::uuid AND device_hash=proof;
  IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;
 END IF;
 RETURN jsonb_build_object('prepared',COALESCE((result->>'pending')::boolean,false));
END $$;
REVOKE ALL ON FUNCTION public.priperi_replacement(text,uuid,uuid,text,text,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_replacement(text,uuid,uuid,text,text,jsonb) TO service_role;

-- Patch only the tested prior definitions, preserving their grants and checks.
DO $migration$
DECLARE definition text;
BEGIN
 SELECT pg_get_functiondef('public.priperi_begin_device(text,text,uuid,text,text,text,uuid,uuid)'::regprocedure) INTO definition;
 IF strpos(definition,$before$ IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value$before$)=0 THEN RAISE EXCEPTION 'Unexpected begin_device definition';END IF;
 definition:=replace(definition,$before$ IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value$before$,$after$
 SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof AND installation=installation_id AND fingerprint=fingerprint_value AND device_id=device_id_value;
 IF FOUND AND pending.replaces_uid IS NOT NULL THEN
  IF expected_actor IS NOT NULL OR pending.hpke<>hpke_key OR pending.signing<>signing_key OR pending.replacement_until<=now()
   OR NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=pending.replaces_uid AND device_hash=pending.replaces_hash AND device_id=device_id_value)
   OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=pending.replaces_uid AND ((completed_at IS NULL AND expires_at>now()) OR (completed_at IS NOT NULL AND confirmed_at IS NULL))) THEN RETURN '{"denied":true}'::jsonb;END IF;
  RETURN jsonb_build_object('uid',pending.uid,'pending',true);
 END IF;
 IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value$after$);
 EXECUTE definition;
 SELECT pg_get_functiondef('public.priperi_device(text,uuid,text,uuid,text,jsonb)'::regprocedure) INTO definition;
 IF strpos(definition,$before$ IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value$before$)=0 THEN RAISE EXCEPTION 'Unexpected device definition';END IF;
 definition:=replace(definition,$before$ IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value$before$,$after$
 IF action='status' THEN
  SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof AND installation=installation_id AND fingerprint=fingerprint_value AND device_id=device_id_value AND replaces_uid IS NOT NULL AND replacement_until>now();
  IF FOUND AND EXISTS(SELECT 1 FROM relay.profiles WHERE uid=pending.replaces_uid AND device_hash=pending.replaces_hash AND device_id=device_id_value) THEN
   IF NOT relay.charge(proof,'device_status',30,60) THEN RETURN '{"limited":true}'::jsonb;END IF;
   RETURN jsonb_build_object('registered',false,'pendingUsername',pending.username,'replacement',true);
  END IF;
 END IF;
 IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value$after$);
 EXECUTE definition;
 SELECT pg_get_functiondef('public.priperi_finish(uuid,text)'::regprocedure) INTO definition;
 IF strpos(definition,'DECLARE p relay.profiles;')=0 OR strpos(definition,' INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation,device_id)')=0 THEN RAISE EXCEPTION 'Unexpected finish definition';END IF;
 definition:=replace(definition,'DECLARE p relay.profiles;','DECLARE p relay.profiles; replacement relay.pending_accounts;');
 definition:=replace(definition,$before$ INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation,device_id)$before$,$after$
 SELECT * INTO replacement FROM relay.pending_accounts WHERE uid=actor AND device_hash=proof;
 IF FOUND AND replacement.replaces_uid IS NOT NULL THEN
  IF replacement.replacement_until<=now() OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=replacement.replaces_uid AND ((completed_at IS NULL AND expires_at>now()) OR (completed_at IS NOT NULL AND confirmed_at IS NULL))) THEN RAISE EXCEPTION 'denied';END IF;
  UPDATE relay.profiles SET device_id=NULL,idle_days=7,active_at=now(),fcm_token=NULL WHERE uid=replacement.replaces_uid AND device_hash=replacement.replaces_hash AND device_id=replacement.device_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;
  DELETE FROM auth.sessions WHERE user_id=replacement.replaces_uid;
  DELETE FROM relay.reset_challenges WHERE uid=replacement.replaces_uid;
 END IF;
 INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation,device_id)$after$);
 EXECUTE definition;
 SELECT pg_get_functiondef('relay.erase_account(uuid)'::regprocedure) INTO definition;
 IF strpos(definition,' DELETE FROM relay.rates WHERE subject IN(actor::text,me.device_hash)')=0 THEN RAISE EXCEPTION 'Unexpected erase definition';END IF;
 definition:=replace(definition,' DELETE FROM relay.rates WHERE subject IN(actor::text,me.device_hash)',' DELETE FROM relay.rates WHERE subject IN(actor::text,me.device_hash) OR (me.device_id IS NOT NULL AND subject=me.device_id::text AND kind=''replacement'')');
 EXECUTE definition;
END $migration$;
NOTIFY pgrst,'reload schema';
COMMIT;
