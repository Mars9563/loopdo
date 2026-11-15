-- LoopDo 0005: let the backup role read user accounts without access to Supabase's
-- auth schema (which `postgres` may not grant on Supabase Cloud). Views run with
-- their owner's (postgres) privileges, so loopdo_backup reads through them.
-- pg_dump stores only view definitions; loopdo-daily exports their rows as CSV.

create or replace view loopdo_meta.auth_users_backup as
  select * from auth.users;

revoke all on loopdo_meta.auth_users_backup from public, anon, authenticated;
grant select on loopdo_meta.auth_users_backup to loopdo_backup;

-- auth.identities is created by Supabase Auth itself (absent in a bare Postgres).
do $$
begin
  if to_regclass('auth.identities') is not null then
    execute 'create or replace view loopdo_meta.auth_identities_backup as select * from auth.identities';
    execute 'revoke all on loopdo_meta.auth_identities_backup from public, anon, authenticated';
    execute 'grant select on loopdo_meta.auth_identities_backup to loopdo_backup';
  end if;
end $$;
