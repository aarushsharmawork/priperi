-- SQL scheduling needs no external host, API credential or paid function plan.
CREATE EXTENSION IF NOT EXISTS pg_cron WITH SCHEMA pg_catalog;
SELECT cron.schedule('priperi-expiry','17 * * * *','SELECT relay.sweep()');
