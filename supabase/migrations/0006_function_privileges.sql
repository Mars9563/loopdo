-- LoopDo 0006: functions created later must not be callable through Supabase's /rest/v1/rpc.
-- Postgres gives EXECUTE to PUBLIC on every new function by default, globally; a
-- per-schema "revoke ... from public" (0002) can't remove a global default. This one can.
-- Future functions need an explicit grant (as the sign-up hook already has).

alter default privileges for role postgres revoke execute on functions from public;

-- And re-check everything already in public.
revoke execute on all functions in schema public from public, anon, authenticated;
grant execute on function public.current_user_id() to loopdo_user;
grant execute on function public.hook_before_user_created(jsonb) to supabase_auth_admin;
