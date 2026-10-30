-- Apply after011. Routine privacy fix: remove the abandoned receiver's
-- installation counters when an unconfirmed move is returned. No rows are
-- deleted while applying this migration; function permissions/data are preserved.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
DO $migration$
DECLARE definition text; source_hash text;
BEGIN
 SELECT pg_get_functiondef(p.oid),md5(p.prosrc) INTO definition,source_hash FROM pg_proc p
 WHERE p.oid=to_regprocedure('public.priperi_transfer(uuid,uuid,text,text,jsonb,boolean)');
 IF source_hash='33f92b31eae2a7175b6c0739881a4a7c' THEN RETURN;END IF;
 IF source_hash IS DISTINCT FROM '0d53566b643d92a1b35353da78c79000' THEN RAISE EXCEPTION 'Unexpected transfer definition; review migration011 before applying012';END IF;
 IF strpos(definition,$before$DELETE FROM relay.rates WHERE subject=t.lookup_hash;DELETE FROM relay.transfers WHERE uid=me.uid;$before$)=0 THEN RAISE EXCEPTION 'Rollback cleanup patch site missing';END IF;
 EXECUTE replace(definition,$before$DELETE FROM relay.rates WHERE subject=t.lookup_hash;DELETE FROM relay.transfers WHERE uid=me.uid;$before$,$after$DELETE FROM relay.rates WHERE subject IN(t.lookup_hash,me.device_hash);DELETE FROM relay.transfers WHERE uid=me.uid;$after$);
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('public.priperi_transfer(uuid,uuid,text,text,jsonb,boolean)'))<>'33f92b31eae2a7175b6c0739881a4a7c' THEN RAISE EXCEPTION 'Rollback cleanup verification failed';END IF;
END $migration$;
NOTIFY pgrst,'reload schema';
COMMIT;
