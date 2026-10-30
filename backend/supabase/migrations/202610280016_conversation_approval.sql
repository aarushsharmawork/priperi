-- Apply after015. One explicit recipient approval opens both directions.
-- Existing approvals take effect immediately; no account/message/counter rows are rewritten.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
DO $migration$
DECLARE definition text;
BEGIN
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('public.priperi_request(uuid,uuid,text,text,jsonb,boolean)')) IS DISTINCT FROM 'a45eb11714e25023871086d68a8c72ea'
 OR (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('relay.contact_state(uuid,uuid)')) IS DISTINCT FROM '2b944dca024e0b5dd9ff83cd27222124' THEN RAISE EXCEPTION 'Unexpected approval definitions; review installed001-015 before applying016';END IF;
 SELECT pg_get_functiondef(to_regprocedure('public.priperi_request(uuid,uuid,text,text,jsonb,boolean)')) INTO definition;
 IF strpos(definition,$before$  IF NOT FOUND THEN RAISE EXCEPTION 'no_contact_request'; END IF;$before$)=0 THEN RAISE EXCEPTION 'Approval patch site missing';END IF;
 definition:=replace(definition,$before$  IF NOT FOUND THEN RAISE EXCEPTION 'no_contact_request'; END IF;$before$,$after$  -- Never let an initiator approve an unanswered request. Already accepted pairs are idempotent.
  IF NOT FOUND AND NOT EXISTS(SELECT 1 FROM relay.contact_permissions WHERE approved AND ((sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor))) THEN RAISE EXCEPTION 'no_contact_request'; END IF;$after$);
 IF strpos(definition,$before$  IF FOUND AND NOT permission.approved AND permission.sent_count>=3 THEN RAISE EXCEPTION 'contact_approval_required'; END IF;$before$)=0 THEN RAISE EXCEPTION 'Send approval patch site missing';END IF;
 definition:=replace(definition,$before$  IF FOUND AND NOT permission.approved AND permission.sent_count>=3 THEN RAISE EXCEPTION 'contact_approval_required'; END IF;$before$,$after$  IF FOUND AND permission.sent_count>=3 AND NOT EXISTS(SELECT 1 FROM relay.contact_permissions WHERE approved AND ((sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor))) THEN RAISE EXCEPTION 'contact_approval_required'; END IF;$after$);
 EXECUTE definition;
END $migration$;
CREATE OR REPLACE FUNCTION relay.contact_state(actor uuid,peer_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path='' AS $$
 WITH pair AS (SELECT COALESCE(bool_or(approved),false) AS accepted,COALESCE(bool_or(sender=peer_id),false) AS incoming
  FROM relay.contact_permissions WHERE (sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor))
 SELECT jsonb_build_object('uid',peer_id,
  'sentCount',COALESCE((SELECT sent_count FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id),0),
  'approved',pair.accepted,'incomingPending',pair.incoming AND NOT pair.accepted)
 ||COALESCE((SELECT jsonb_build_object('username',p.username,'fingerprint',p.fingerprint) FROM relay.profiles p WHERE p.uid=peer_id
  AND EXISTS(SELECT 1 FROM relay.contact_permissions WHERE (sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor))
  AND NOT EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=peer_id) OR (owner=peer_id AND target=actor))),'{}'::jsonb)
 FROM pair
$$;
-- CREATE OR REPLACE preserves existing ownership and client-denied grants.
REVOKE ALL ON FUNCTION relay.contact_state(uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
DO $verify$
BEGIN
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid='public.priperi_request(uuid,uuid,text,text,jsonb,boolean)'::regprocedure)<>'583c5f5bdb5a248d8e00f383aa63e5f5'
 OR (SELECT md5(prosrc) FROM pg_proc WHERE oid='relay.contact_state(uuid,uuid)'::regprocedure)<>'37c5340f9503ce623621bcb6d83cf2a0' THEN RAISE EXCEPTION 'Conversation approval patch verification failed';END IF;
END $verify$;
NOTIFY pgrst,'reload schema';
COMMIT;
