-- Supabase's own API roles must not be able to touch LoopDo tables: every write
-- has to go through the LoopDo API (which checks the tamper evidence).
do $$
declare
  tbl text;
  r text;
begin
  foreach tbl in array array['profiles','day_settings_versions','app_config','habits','habit_schedules',
                             'habit_logs','lists','tasks','subtasks','journal_entries','journal_photos','journal_lines','applied_ops','weekly_focus'] loop
    foreach r in array array['anon','authenticated'] loop
      if has_table_privilege(r, 'public.' || tbl, 'select,insert,update,delete') then
        raise exception 'FAIL grants: % has privileges on %', r, tbl;
      end if;
    end loop;
  end loop;
  if has_function_privilege('authenticated', 'public.hook_before_user_created(jsonb)', 'execute')
     or has_function_privilege('anon', 'public.hook_before_user_created(jsonb)', 'execute') then
    raise exception 'FAIL grants: API roles can execute the sign-up hook';
  end if;
  if not has_function_privilege('supabase_auth_admin', 'public.hook_before_user_created(jsonb)', 'execute') then
    raise exception 'FAIL grants: supabase_auth_admin cannot execute the sign-up hook';
  end if;
  if not pg_has_role('loopdo_api', 'loopdo_user', 'set') then
    raise exception 'FAIL grants: loopdo_api cannot switch to loopdo_user';
  end if;
  if pg_has_role('loopdo_api', 'loopdo_user', 'usage') then
    raise exception 'FAIL grants: loopdo_api must not inherit loopdo_user rights (NOINHERIT)';
  end if;
  if (select count(*) from pg_publication_tables where pubname = 'powersync') <> 12 then
    raise exception 'FAIL grants: powersync publication should list 12 tables';
  end if;
  -- M9: only the API itself may claim or wipe accounts; a signed-in user may only ask/keep for their own.
  if has_function_privilege('loopdo_user', 'public.claim_due_accounts()', 'execute')
     or has_function_privilege('loopdo_user', 'public.wipe_claimed_accounts(uuid[])', 'execute')
     or has_function_privilege('authenticated', 'public.wipe_claimed_accounts(uuid[])', 'execute') then
    raise exception 'FAIL grants: a user role can claim or wipe accounts';
  end if;
  if not has_function_privilege('loopdo_api', 'public.wipe_claimed_accounts(uuid[])', 'execute') then
    raise exception 'FAIL grants: loopdo_api cannot run the wipe';
  end if;
  if has_column_privilege('loopdo_user', 'public.profiles', 'delete_requested_at', 'update')
     or has_column_privilege('loopdo_user', 'public.profiles', 'wipe_claimed_at', 'update') then
    raise exception 'FAIL grants: users can write the deletion dates directly';
  end if;
  if not (select rolreplication and rolbypassrls from pg_roles where rolname = 'powersync_role') then
    raise exception 'FAIL grants: powersync_role needs REPLICATION and BYPASSRLS';
  end if;
end $$;

-- Even signed in, the authenticated role (what Supabase's REST API uses) is refused.
begin;
select t.as_user('00000000-0000-0000-0000-00000000000a');
set local role authenticated;
select t.expect_error('select * from public.habits', '42501', 'authenticated reads habits');
select t.expect_error($q$insert into public.habit_logs (user_id, habit_id, loopdo_date, count, verified, evidence)
  values ('00000000-0000-0000-0000-00000000000a', '10000000-0000-0000-0000-00000000000a', '2026-11-14', 1, true, '{}')$q$,
  '42501', 'authenticated writes a verified tick');
rollback;

-- The nightly backup role can read everything (incl. past RLS) and write nothing.
do $$ begin
  if not (select rolbypassrls and rolcanlogin from pg_roles where rolname = 'loopdo_backup') then
    raise exception 'FAIL grants: loopdo_backup must LOGIN with BYPASSRLS';
  end if;
  if not has_table_privilege('loopdo_backup', 'public.habit_logs', 'select')
     or not has_table_privilege('loopdo_backup', 'loopdo_meta.schema_migrations', 'select') then
    raise exception 'FAIL grants: loopdo_backup cannot read LoopDo tables';
  end if;
  if has_table_privilege('loopdo_backup', 'public.habit_logs', 'insert,update,delete') then
    raise exception 'FAIL grants: loopdo_backup must be read-only';
  end if;
end $$;

-- Accounts are backed up through views owned by postgres (no direct access to the auth schema).
do $$ begin
  if to_regclass('loopdo_meta.auth_users_backup') is null then
    raise exception 'FAIL grants: loopdo_meta.auth_users_backup view missing';
  end if;
  if not has_table_privilege('loopdo_backup', 'loopdo_meta.auth_users_backup', 'select') then
    raise exception 'FAIL grants: loopdo_backup cannot read the accounts backup view';
  end if;
  if has_table_privilege('authenticated', 'loopdo_meta.auth_users_backup', 'select')
     or has_table_privilege('anon', 'loopdo_meta.auth_users_backup', 'select') then
    raise exception 'FAIL grants: API roles can read the accounts backup view';
  end if;
end $$;
begin;
set local role loopdo_backup;
do $$ begin
  if (select count(*) from loopdo_meta.auth_users_backup) < 2 then
    raise exception 'FAIL grants: loopdo_backup does not see the users through the view';
  end if;
end $$;
rollback;

-- Functions made by later migrations (as postgres) must not be callable through Supabase's /rpc.
set role postgres;
create function public.t_future_fn() returns int language sql as 'select 1';
reset role;
do $$
declare f text;
begin
  if has_function_privilege('anon', 'public.t_future_fn()', 'execute')
     or has_function_privilege('authenticated', 'public.t_future_fn()', 'execute') then
    raise exception 'FAIL grants: a newly created function is executable by anon/authenticated';
  end if;
  for f in select p.oid::regprocedure::text from pg_proc p where p.pronamespace = 'public'::regnamespace loop
    if has_function_privilege('anon', f, 'execute') or has_function_privilege('authenticated', f, 'execute') then
      raise exception 'FAIL grants: % is executable by anon/authenticated', f;
    end if;
  end loop;
end $$;
drop function public.t_future_fn();
