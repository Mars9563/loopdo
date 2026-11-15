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
