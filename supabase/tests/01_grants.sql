-- Supabase's own API roles must not be able to touch LoopDo tables: every write
-- has to go through the LoopDo API (which checks the tamper evidence).
do $$
declare
  tbl text;
  r text;
begin
  foreach tbl in array array['profiles','day_settings_versions','app_config','habits','habit_schedules',
                             'habit_logs','lists','tasks','subtasks','journal_entries','journal_photos'] loop
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
  if (select count(*) from pg_publication_tables where pubname = 'powersync') <> 10 then
    raise exception 'FAIL grants: powersync publication should list 10 tables';
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
