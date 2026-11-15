#!/usr/bin/env bash
# Dev seed: puts the locked today mockup's habits, lists and tasks into ONE account, so the
# today screen has real data before the M5 habit editor exists. No ticks are seeded (ticks
# need evidence and can't be backdated). Every row has a fixed id (uuid5), so running it
# again changes nothing and --remove deletes exactly these rows.
#
#   scripts/dev-seed.sh <user-id>            seed
#   scripts/dev-seed.sh --remove <user-id>   remove the seeded rows (and their logs)
#
# Needs ~/.config/loopdo/supabase.env (SUPABASE_DB_URL). Rows are written as the database
# owner; PowerSync syncs them to the phone like any other change.
set -euo pipefail

remove=0
if [ "${1:-}" = --remove ]; then remove=1; shift; fi
USER_ID=${1:?usage: dev-seed.sh [--remove] <user-id>}
python3 -I -c "import uuid,sys; uuid.UUID(sys.argv[1])" "$USER_ID" 2>/dev/null || { echo "not a uuid: $USER_ID" >&2; exit 2; }

cfg=${LOOPDO_CONFIG_DIR:-$HOME/.config/loopdo}
DB_URL=$(sed -n 's/^SUPABASE_DB_URL=//p' "$cfg/supabase.env" | head -1 | sed "s/^[\"']//; s/[\"']\$//")
sql() { PGCONNECT_TIMEOUT=20 psql -X -q -tA -v ON_ERROR_STOP=1 "$DB_URL" "$@"; }

# Seed ids: uuid5(uuid5(NAMESPACE_URL, "loopdo-dev-seed:<user>"), <name>).
ids=$(python3 -I - "$USER_ID" <<'PY'
import sys, uuid
ns = uuid.uuid5(uuid.NAMESPACE_URL, "loopdo-dev-seed:" + sys.argv[1])
names = ["h_water", "h_gym", "h_med", "h_jp", "s_water", "s_gym", "s_med", "s_jp", "l_work", "l_jh"] + \
        [f"t{i}" for i in range(14)]
print(" ".join(f"{n}={uuid.uuid5(ns, n)}" for n in names))
PY
)
declare -A id
for kv in $ids; do id[${kv%%=*}]=${kv#*=}; done
U=$USER_ID

if [ "$remove" = 1 ]; then
  all=$(printf "'%s'," "${id[@]}"); all=${all%,}
  sql <<SQL
begin;
delete from public.tasks where user_id = '$U' and id in ($all);
delete from public.habit_logs where user_id = '$U' and habit_id in ($all);
delete from public.habit_schedules where user_id = '$U' and id in ($all);
delete from public.habits where user_id = '$U' and id in ($all);
delete from public.lists where user_id = '$U' and id in ($all);
commit;
SQL
  echo "removed the dev seed for $U"
  exit 0
fi

TODAY=$(sql -c "select ((now() at time zone time_zone) - day_end::interval)::date from public.day_settings_versions
                where user_id = '$U' order by effective_at desc limit 1")
[ -n "$TODAY" ] || { echo "that user has no day settings (finish setup in the app first)" >&2; exit 1; }

sql <<SQL
begin;
insert into public.habits (id, user_id, title, kind, target, reminder_time, sort_order) values
  ('${id[h_water]}', '$U', 'water', 'count', 8, null, 0),
  ('${id[h_gym]}', '$U', 'gym', 'yes_no', 1, '18:00', 1),
  ('${id[h_med]}', '$U', 'meditate', 'yes_no', 1, null, 2),
  ('${id[h_jp]}', '$U', 'japanese 20m', 'yes_no', 1, null, 3)
on conflict (id) do nothing;
insert into public.habit_schedules (id, user_id, habit_id, valid_from, recurrence, weekdays) values
  ('${id[s_water]}', '$U', '${id[h_water]}', '$TODAY', 'daily', null),
  ('${id[s_gym]}', '$U', '${id[h_gym]}', '$TODAY', 'weekdays', '{1,3,5,6,7}'),
  ('${id[s_med]}', '$U', '${id[h_med]}', '$TODAY', 'daily', null),
  ('${id[s_jp]}', '$U', '${id[h_jp]}', '$TODAY', 'daily', null)
on conflict (id) do nothing;
insert into public.lists (id, user_id, name, sort_order) values
  ('${id[l_work]}', '$U', 'work', 0),
  ('${id[l_jh]}', '$U', 'job hunt', 1)
on conflict (id) do nothing;
insert into public.tasks (id, user_id, list_id, title, due_date, sort_order) values
  ('${id[t0]}', '$U', null, 'pay rent', date '$TODAY' - 1, 0),
  ('${id[t1]}', '$U', '${id[l_jh]}', 'apply: 2 jobs', '$TODAY', 1),
  ('${id[t2]}', '$U', null, 'call home', '$TODAY', 2),
  ('${id[t3]}', '$U', null, 'read compose docs', null, 3),
  ('${id[t4]}', '$U', null, 'fix bike brake', null, 4),
  ('${id[t5]}', '$U', null, 'renew passport', null, 5),
  ('${id[t6]}', '$U', null, 'back up release key', null, 6),
  ('${id[t7]}', '$U', '${id[l_work]}', 'write sync notes', null, 7),
  ('${id[t8]}', '$U', '${id[l_work]}', 'review pr', null, 8),
  ('${id[t9]}', '$U', '${id[l_work]}', 'plan m4', null, 9),
  ('${id[t10]}', '$U', '${id[l_jh]}', 'update resume', null, 10),
  ('${id[t11]}', '$U', '${id[l_jh]}', 'cold email: 1 founder', null, 11),
  ('${id[t12]}', '$U', '${id[l_jh]}', 'prep: system design', null, 12),
  ('${id[t13]}', '$U', '${id[l_jh]}', 'portfolio case study', null, 13)
on conflict (id) do nothing;
commit;
SQL
echo "seeded $U for $TODAY: 4 habits, 2 lists, 14 tasks (remove with --remove)"
