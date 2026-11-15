-- Test helpers and fixtures (runs after the migrations, as supabase_admin).
create schema if not exists t;
grant usage on schema t to public;

-- Act as a signed-in user inside the current transaction.
create or replace function t.as_user(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, true);
$$;

-- Run sql and require that it fails with SQLSTATE expected_state.
create or replace function t.expect_error(sql text, expected_state text, label text) returns void
language plpgsql as $$
begin
  begin
    execute sql;
  exception when others then
    if sqlstate <> expected_state then
      raise exception 'FAIL %: expected % but got % (%)', label, expected_state, sqlstate, sqlerrm;
    end if;
    return;
  end;
  raise exception 'FAIL %: expected error % but the statement succeeded', label, expected_state;
end $$;
grant execute on all functions in schema t to public;

-- Two users. The profile trigger should create their profiles.
insert into auth.users (id, email, aud, role)
values ('00000000-0000-0000-0000-00000000000a', 'a@test.local', 'authenticated', 'authenticated'),
       ('00000000-0000-0000-0000-00000000000b', 'b@test.local', 'authenticated', 'authenticated');

-- One habit + journal entry per user, inserted as the table owner (bypasses RLS).
set role postgres;
insert into public.habits (id, user_id, title, kind, target)
values ('10000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a', 'meditate', 'yes_no', 1),
       ('10000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-00000000000b', 'gym', 'yes_no', 1);
insert into public.journal_entries (id, user_id, loopdo_date, text)
values ('20000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a', '2026-11-14', 'hello');
reset role;
