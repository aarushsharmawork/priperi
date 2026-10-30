SELECT jsonb_build_object(
 'relayTablesWithoutRls',(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='relay' AND c.relkind IN ('r','p') AND NOT c.relrowsecurity),
 'clientTableGrants',(SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema='relay' AND grantee IN ('PUBLIC','anon','authenticated')),
 'clientRpcGrants',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'priperi_%' AND (has_function_privilege('anon',p.oid,'EXECUTE') OR has_function_privilege('authenticated',p.oid,'EXECUTE'))),
 'unsafeDefinerSearchPaths',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE p.prosecdef AND (n.nspname='relay' OR n.nspname='public' AND p.proname LIKE 'priperi_%') AND NOT COALESCE(p.proconfig,'{}') @> ARRAY['search_path=""']),
 'anonRelayUsage',has_schema_privilege('anon','relay','USAGE'),
 'authenticatedRelayUsage',has_schema_privilege('authenticated','relay','USAGE'),
 'clientBypassRls',(SELECT bool_or(rolbypassrls OR rolsuper) FROM pg_roles WHERE rolname IN ('anon','authenticated')),
 'relayRealtimePublications',(SELECT count(*) FROM pg_publication_tables WHERE schemaname='relay'),
 'publicStorageBuckets',(SELECT count(*) FROM storage.buckets WHERE public),
 'storagePolicyCount',(SELECT count(*) FROM pg_policies WHERE schemaname='storage'),
 'routineDigest',(SELECT md5(string_agg(n.nspname||'.'||p.proname||'('||oidvectortypes(p.proargtypes)||'):'||md5(p.prosrc),E'\n' ORDER BY n.nspname,p.proname,oidvectortypes(p.proargtypes))) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='relay' OR n.nspname='public' AND p.proname LIKE 'priperi_%')
) AS security_config;
SELECT jsonb_build_object(
 'publicDefinersExecutableByClients',COALESCE((SELECT jsonb_agg(p.proname) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prosecdef AND (has_function_privilege('anon',p.oid,'EXECUTE') OR has_function_privilege('authenticated',p.oid,'EXECUTE'))),'[]'::jsonb),
 'publicTablesWithoutRls',(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT c.relrowsecurity),
 'publicTablesWithClientGrants',(SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema='public' AND grantee IN ('PUBLIC','anon','authenticated'))
) AS other_entrypoints;
