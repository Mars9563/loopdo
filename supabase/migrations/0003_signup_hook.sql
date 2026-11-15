-- LoopDo 0003: the sign-up switch. Supabase Auth calls this before creating a user
-- (Auth → Hooks → Before User Created → Postgres function public.hook_before_user_created).

create or replace function public.hook_before_user_created(event jsonb) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if coalesce((select signups_enabled from public.app_config where id = 1), true) then
    return '{}'::jsonb;
  end if;
  return jsonb_build_object('error', jsonb_build_object('http_code', 403, 'message', 'Sign-ups are closed'));
end $$;

revoke all on function public.hook_before_user_created(jsonb) from public, anon, authenticated;
grant usage on schema public to supabase_auth_admin;
grant execute on function public.hook_before_user_created(jsonb) to supabase_auth_admin;
