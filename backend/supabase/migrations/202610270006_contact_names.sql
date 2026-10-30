-- Apply once after001–005. No new table, grant, secret or permission.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
CREATE OR REPLACE FUNCTION relay.contact_state(actor uuid,peer_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path='' AS $$
 SELECT jsonb_build_object('uid',peer_id,
  'sentCount',COALESCE((SELECT sent_count FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id),0),
  'approved',COALESCE((SELECT approved FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id),false),
  'incomingPending',EXISTS(SELECT 1 FROM relay.contact_permissions WHERE sender=peer_id AND recipient=actor AND NOT approved))
 ||COALESCE((SELECT jsonb_build_object('username',p.username,'fingerprint',p.fingerprint) FROM relay.profiles p WHERE p.uid=peer_id
  AND EXISTS(SELECT 1 FROM relay.contact_permissions WHERE (sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor))
  AND NOT EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=peer_id) OR (owner=peer_id AND target=actor))),'{}'::jsonb)
$$;
REVOKE ALL ON FUNCTION relay.contact_state(uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
NOTIFY pgrst, 'reload schema';
COMMIT;
