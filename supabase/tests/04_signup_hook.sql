-- The hook runs as supabase_auth_admin, like GoTrue calls it.
begin;
set local role supabase_auth_admin;
do $$ begin
  if public.hook_before_user_created('{"user": {"email": "x@test.local"}}'::jsonb) <> '{}'::jsonb then
    raise exception 'FAIL hook: sign-ups should be open by default';
  end if;
end $$;
rollback;

update public.app_config set signups_enabled = false;
begin;
set local role supabase_auth_admin;
do $$
declare res jsonb;
begin
  res := public.hook_before_user_created('{"user": {"email": "x@test.local"}}'::jsonb);
  if (res -> 'error' ->> 'http_code')::int <> 403 then
    raise exception 'FAIL hook: closed sign-ups should return 403, got %', res;
  end if;
end $$;
rollback;
update public.app_config set signups_enabled = true;

-- New users get a profile automatically.
do $$ begin
  if (select count(*) from public.profiles where id in ('00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000b')) <> 2 then
    raise exception 'FAIL hook: profiles were not created on sign-up';
  end if;
end $$;

-- A real sign-up inserts into auth.users as supabase_auth_admin (not a superuser):
-- the profile trigger must still fire after function EXECUTE was revoked from PUBLIC (0006).
begin;
set local role supabase_auth_admin;
insert into auth.users (id, email, aud, role)
values ('00000000-0000-0000-0000-0000000000cc', 'c@test.local', 'authenticated', 'authenticated');
reset role;
do $$ begin
  if not exists (select 1 from public.profiles where id = '00000000-0000-0000-0000-0000000000cc') then
    raise exception 'FAIL hook: profile trigger did not fire for a sign-up by supabase_auth_admin';
  end if;
end $$;
rollback;
