-- LoopDo 0011 (M9, delete account with a 7-day undo). Only adds columns and functions; nothing is removed.
-- A request waits 7 days (signing in and choosing "keep" cancels it); then the API's daily job claims the account
-- (keep is refused from then on), deletes its photos in R2, and wipes the sign-in, which cascades to every table.
-- The functions are narrow on purpose: the API never gets Supabase's master key (owner, 2026-11-18).
-- Design: docs/superpowers/specs/2026-11-18-m9-finish-design.md (private).

alter table public.profiles add column if not exists delete_requested_at timestamptz;
alter table public.profiles add column if not exists wipe_claimed_at timestamptz;
-- (loopdo_user's update grant stays display_name, onboarded_at, settings: these two change only through the functions.)

-- The signed-in user asks to delete their account; returns when it was asked (a second ask keeps the first date).
create or replace function public.request_account_deletion() returns timestamptz
language plpgsql security definer set search_path = '' as $$
declare t timestamptz;
begin
  update public.profiles set delete_requested_at = coalesce(delete_requested_at, now())
    where id = public.current_user_id() and wipe_claimed_at is null
    returning delete_requested_at into t;
  if t is null then raise exception 'WIPE_STARTED' using errcode = 'P0001'; end if;
  return t;
end $$;

-- The signed-in user changes their mind; refused once the wipe has started. One statement, so a keep can never
-- slip between the job's claim and its wipe (final review I1): the row lock decides who was first.
create or replace function public.keep_account() returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles set delete_requested_at = null
    where id = public.current_user_id() and wipe_claimed_at is null;
  if not found then raise exception 'WIPE_STARTED' using errcode = 'P0001'; end if;
end $$;

-- The daily job: mark accounts whose request is 7+ days old, and return every claimed one (claims are sticky).
create or replace function public.claim_due_accounts() returns setof uuid
language plpgsql security definer set search_path = '' as $$
begin
  update public.profiles set wipe_claimed_at = now()
    where delete_requested_at <= now() - interval '7 days' and wipe_claimed_at is null;
  return query select id from public.profiles where wipe_claimed_at is not null and delete_requested_at is not null;
end $$;

-- The daily job, after their photos are gone: delete these claimed sign-ins (every loopdo_ table cascades from
-- auth.users). Only the ids this run cleaned, and only if they are still claimed and still asking to be deleted.
create or replace function public.wipe_claimed_accounts(ids uuid[]) returns integer
language plpgsql security definer set search_path = '' as $$
declare n integer;
begin
  delete from auth.users u using public.profiles p
    where p.id = u.id and p.id = any(ids) and p.wipe_claimed_at is not null and p.delete_requested_at is not null;
  get diagnostics n = row_count;
  return n;
end $$;

revoke all on function public.request_account_deletion(), public.keep_account(),
  public.claim_due_accounts(), public.wipe_claimed_accounts(uuid[]) from public;
grant execute on function public.request_account_deletion(), public.keep_account() to loopdo_user;
grant execute on function public.claim_due_accounts(), public.wipe_claimed_accounts(uuid[]) to loopdo_api;
