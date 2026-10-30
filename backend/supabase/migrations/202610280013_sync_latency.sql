-- Apply after012. Preserve tables, grants, accounts, queues and all existing checks.
-- Move sequential acknowledgement RPCs into one server-only transaction and
-- fetch direct/group changes together. No client gains a database permission.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
DO $migration$
DECLARE definition text; source_hash text;
BEGIN
 SELECT pg_get_functiondef(p.oid),md5(p.prosrc) INTO definition,source_hash FROM pg_proc p
 WHERE p.oid=to_regprocedure('public.priperi_request(uuid,uuid,text,text,jsonb,boolean)');
 IF source_hash='a45eb11714e25023871086d68a8c72ea' THEN RETURN;END IF;
 IF source_hash IS DISTINCT FROM '65064c0fb5c382d0c7f061627cc89187' THEN RAISE EXCEPTION 'Unexpected request definition; review migration011 before applying013';END IF;
 IF strpos(definition,$before$ IF operation='getAccount' THEN RETURN relay.profile(me);$before$)=0 THEN RAISE EXCEPTION 'Send context patch site missing';END IF;
 definition:=replace(definition,$before$ IF operation='getAccount' THEN RETURN relay.profile(me);$before$,$after$
 IF operation='sendContext' THEN
  IF NOT public.priperi_attempt(actor,session_id,proof,'send') THEN RETURN '{"attemptDenied":true}'::jsonb;END IF;
  RETURN relay.profile(me);
 ELSIF operation='getAccount' THEN RETURN relay.profile(me);$after$);
 IF strpos(definition,$before$ ELSIF operation='acknowledgeMessage' THEN$before$)=0 THEN RAISE EXCEPTION 'Acknowledgement patch site missing';END IF;
 definition:=replace(definition,$before$ ELSIF operation='acknowledgeMessage' THEN$before$,$after$
 -- priperi-sync-latency-v1: the Edge has already charged the first ID.
 ELSIF operation='acknowledgeBatch' THEN
  IF jsonb_typeof(payload->'ids') IS DISTINCT FROM 'array' OR jsonb_array_length(payload->'ids') NOT BETWEEN 1 AND 50 THEN RAISE EXCEPTION 'denied';END IF;
  IF (SELECT count(DISTINCT value) FROM jsonb_array_elements_text(payload->'ids'))<>jsonb_array_length(payload->'ids') THEN RAISE EXCEPTION 'denied';END IF;
  output:='[]'::jsonb;
  FOR m IN SELECT value FROM jsonb_array_elements(payload->'ids') LOOP
   IF jsonb_array_length(output)>0 AND NOT public.priperi_attempt(actor,session_id,proof,'ack') THEN RETURN '{"batchDenied":true}'::jsonb;END IF;
   message_id:=(m#>>'{}')::uuid;
   SELECT * INTO item FROM relay.messages WHERE id=message_id;
   -- Return rather than raise: previous valid acks and their charges must commit
   -- even when a later ID belongs to somebody else or exceeds the quota.
   IF FOUND AND item.recipient<>actor THEN RETURN '{"batchDenied":true}'::jsonb;END IF;
   m:=public.priperi_request(actor,session_id,proof,'acknowledgeMessage',jsonb_build_object('id',message_id),false);
   IF COALESCE((m->>'accountExpired')::boolean,false) THEN RETURN m;END IF;
   output:=output||jsonb_build_array(message_id);
  END LOOP;
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='acknowledgeMessage' THEN$after$);
 IF strpos(definition,$before$  RETURN output||jsonb_build_object('contactStates',$before$)=0 THEN RAISE EXCEPTION 'Sync patch site missing';END IF;
 definition:=replace(definition,$before$  RETURN output||jsonb_build_object('contactStates',$before$,$after$
  output:=output||jsonb_build_object('groupData',public.priperi_groups(actor,session_id,proof,'syncGroups',jsonb_build_object('tags',COALESCE(payload->'groupTags','{}'::jsonb))),
   'senderProfiles',COALESCE((SELECT jsonb_agg(relay.profile(p)) FROM relay.profiles p WHERE p.uid IN
    (SELECT (value->>'sender')::uuid FROM jsonb_array_elements(output->'messages'))
    AND NOT EXISTS(SELECT 1 FROM relay.blocked b WHERE b.owner=p.uid AND b.target=actor)),'[]'::jsonb));
  RETURN output||jsonb_build_object('contactStates',$after$);
 EXECUTE definition;
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('public.priperi_request(uuid,uuid,text,text,jsonb,boolean)'))<>'a45eb11714e25023871086d68a8c72ea' THEN RAISE EXCEPTION 'Sync latency patch verification failed';END IF;
END $migration$;
NOTIFY pgrst,'reload schema';
COMMIT;
