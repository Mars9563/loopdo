-- LoopDo 0004: a read-only role for the nightly pg_dump on ubuntu-oracle, so the
-- server never needs the project's master `postgres` password.
-- BYPASSRLS is required: pg_dump refuses to dump tables whose rows RLS would hide.

create schema if not exists loopdo_meta;
revoke all on schema loopdo_meta from public;
create table if not exists loopdo_meta.schema_migrations (
  name text primary key,
  applied_at timestamptz not null default now()
);

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'loopdo_backup') then
    create role loopdo_backup login bypassrls;
  end if;
end $$;

grant usage on schema public, loopdo_meta to loopdo_backup;
grant select on all tables in schema public to loopdo_backup;
grant select on all tables in schema loopdo_meta to loopdo_backup;
grant select on all sequences in schema public to loopdo_backup;
alter default privileges in schema public grant select on tables to loopdo_backup;
alter default privileges in schema loopdo_meta grant select on tables to loopdo_backup;

-- Also read the users and their login identities, so a restore can recreate them.
-- Supabase may not allow granting on its auth schema; the dump then covers public data only.
do $$
begin
  grant usage on schema auth to loopdo_backup;
  grant select on auth.users, auth.identities to loopdo_backup;
exception when others then
  raise warning 'loopdo_backup cannot read auth.users/auth.identities (%); backups will not include accounts', sqlerrm;
end $$;
