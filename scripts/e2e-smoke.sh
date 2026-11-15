#!/usr/bin/env bash
# End-to-end smoke test against the live services, with a throwaway user.
#
#   scripts/e2e-smoke.sh [--signup-check]
#
# Needs ~/.config/loopdo/supabase.env (SUPABASE_DB_URL) for creating/deleting the test
# user. --signup-check also flips the sign-up switch and expects Supabase Auth to refuse
# a sign-up (requires the Before-User-Created hook to be enabled in the dashboard).
set -euo pipefail

API=${LOOPDO_API:-https://loopdo.mrsdev.in}
SUPA=${LOOPDO_SUPABASE_URL:-https://jcnenvgdlryxlhsnkaiq.supabase.co}
KEY=${LOOPDO_PUBLISHABLE_KEY:-sb_publishable_s1x-441V8Vb2tHHivRNPIQ_x61QCk8b}
PS=${LOOPDO_POWERSYNC_URL:-https://6af99fc3e5558a7422ba3c92.powersync.journeyapps.com}
cfg=${LOOPDO_CONFIG_DIR:-$HOME/.config/loopdo}
DB_URL=$(sed -n 's/^SUPABASE_DB_URL=//p' "$cfg/supabase.env" | head -1 | sed "s/^[\"']//; s/[\"']\$//")

fail=0
pass() { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; fail=1; }
sql() { PGCONNECT_TIMEOUT=20 psql -X -q -tA -v ON_ERROR_STOP=1 "$DB_URL" "$@"; }
json() { python3 -I -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

EMAIL="e2e-$(date +%s)-$RANDOM@loopdo.invalid"
PASSWORD=$(openssl rand -hex 16)
USER_ID=$(python3 -I -c 'import uuid; print(uuid.uuid4())')

# Remember the sign-up switch and restore exactly that (only the --signup-check step touches it).
SIGNUPS_BEFORE=$(sql -c "select signups_enabled from public.app_config")
SIGNUPS_TOUCHED=0
cleanup() {
  if [ "$SIGNUPS_TOUCHED" = 1 ]; then
    sql -c "update public.app_config set signups_enabled = $( [ "$SIGNUPS_BEFORE" = t ] && echo true || echo false )" >/dev/null 2>&1 || true
  fi
  sql -c "delete from auth.users where id = '$USER_ID' or email like 'e2e-%@loopdo.invalid'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 1. A confirmed test user (no email is sent).
sql <<SQL >/dev/null
insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, recovery_token, email_change_token_new, email_change)
values ('00000000-0000-0000-0000-000000000000', '$USER_ID', 'authenticated', 'authenticated', '$EMAIL',
  extensions.crypt('$PASSWORD', extensions.gen_salt('bf')), now(),
  '{"provider":"email","providers":["email"]}', '{}', now(), now(), '', '', '', '');
insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
values (gen_random_uuid(), '$USER_ID', '$USER_ID',
  jsonb_build_object('sub', '$USER_ID', 'email', '$EMAIL', 'email_verified', true), 'email', now(), now(), now());
SQL
[ "$(sql -c "select count(*) from public.profiles where id = '$USER_ID'")" = 1 ] \
  && pass "user created, profile made by trigger" || bad "profile not created"

# 2. Log in through Supabase Auth.
TOKEN=$(curl -s -m 20 -X POST "$SUPA/auth/v1/token?grant_type=password" -H "apikey: $KEY" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | json "d.get('access_token','')")
[ -n "$TOKEN" ] && pass "login" || { bad "login"; exit 1; }
auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# 3. Day settings (onboarding).
code=$(curl -s -m 20 -o /dev/null -w '%{http_code}' "${auth[@]}" -X POST "$API/api/day-settings" \
  -d '{"timeZone":"Asia/Kolkata","dayEnd":"04:00"}')
[ "$code" = 200 ] && pass "day settings" || bad "day settings HTTP $code"

# 4. Signed server time.
TIME_JSON=$(curl -s -m 20 "${auth[@]}" "$API/api/time")
SERVER_TIME=$(echo "$TIME_JSON" | json "d['serverTime']")
SIG=$(echo "$TIME_JSON" | json "d['sig']")
[ -n "$SIG" ] && pass "server time $SERVER_TIME" || bad "server time"

# Today's and yesterday's LoopDo dates (India, day end 04:00).
read -r TODAY YESTERDAY < <(python3 -I -c "
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
t = datetime.fromisoformat('$SERVER_TIME'.replace('Z', '+00:00')).astimezone(ZoneInfo('Asia/Kolkata')) - timedelta(hours=4)
print(t.date(), t.date() - timedelta(days=1))")

HABIT=$(python3 -I -c 'import uuid; print(uuid.uuid4())')
SCHED=$(python3 -I -c 'import uuid; print(uuid.uuid4())')
LOG=$(python3 -I -c 'import uuid; print(uuid.uuid4())')
BAD_LOG=$(python3 -I -c 'import uuid; print(uuid.uuid4())')

evidence() { # claimedDate -> evidence JSON: anchored to the server time just received, same boot
  echo "{\"claimedDate\":\"$1\",\"wallClock\":\"$SERVER_TIME\",\"anchor\":{\"serverTime\":\"$SERVER_TIME\",\"elapsedRealtimeMs\":1000,\"bootCount\":1,\"sig\":\"$SIG\"},\"elapsedAtTickMs\":2000,\"bootCountAtTick\":1}"
}

# 5. Habit + schedule + a valid tick for today.
BODY=$(cat <<JSON
{"ops":[
 {"op":"PUT","table":"habits","id":"$HABIT","data":{"title":"e2e meditate","kind":"yes_no","target":1}},
 {"op":"PUT","table":"habit_schedules","id":"$SCHED","data":{"habit_id":"$HABIT","valid_from":"$TODAY","recurrence":"daily"}},
 {"op":"PUT","table":"habit_logs","id":"$LOG","data":{"habit_id":"$HABIT","loopdo_date":"$TODAY","count":1,"completed_at":"$SERVER_TIME","evidence":$(evidence "$TODAY")}}
]}
JSON
)
r=$(curl -s -m 20 -w ' %{http_code}' "${auth[@]}" -X POST "$API/api/sync/upload" -d "$BODY")
[ "${r##* }" = 200 ] && pass "upload habit + tick for $TODAY" || bad "upload: $r"
[ "$(sql -c "select verified from public.habit_logs where id = '$LOG'")" = t ] && pass "tick stored as verified" || bad "tick not verified"

# 6. A tick claiming yesterday is refused.
BODY="{\"ops\":[{\"op\":\"PUT\",\"table\":\"habit_logs\",\"id\":\"$BAD_LOG\",\"data\":{\"habit_id\":\"$HABIT\",\"loopdo_date\":\"$YESTERDAY\",\"count\":1,\"completed_at\":\"$SERVER_TIME\",\"evidence\":$(evidence "$YESTERDAY")}}]}"
r=$(curl -s -m 20 -w ' %{http_code}' "${auth[@]}" -X POST "$API/api/sync/upload" -d "$BODY")
[ "${r##* }" = 422 ] && echo "$r" | grep -q BACKDATED && pass "backdated tick refused: ${r% *}" || bad "backdated tick not refused: $r"

# 7. PowerSync streams the new rows down to this user.
STREAM=$(curl -s -N -m 15 -X POST "$PS/sync/stream" -H "Authorization: Token $TOKEN" \
  -H 'Content-Type: application/json' -d '{"raw_data":true,"include_checksum":true}' || true)
echo "$STREAM" | grep -q "$LOG" && pass "PowerSync streams the tick" || bad "PowerSync stream missing the tick ($(echo "$STREAM" | head -c 300))"
echo "$STREAM" | grep -q "$HABIT" && pass "PowerSync streams the habit" || bad "PowerSync stream missing the habit"

# 8. Optional: the sign-up switch (Before-User-Created hook).
if [ "${1:-}" = --signup-check ]; then
  SIGNUPS_TOUCHED=1
  sql -c "update public.app_config set signups_enabled = false" >/dev/null
  r=$(curl -s -m 20 -w ' %{http_code}' -X POST "$SUPA/auth/v1/signup" -H "apikey: $KEY" -H 'Content-Type: application/json' \
    -d "{\"email\":\"e2e-closed-$RANDOM@loopdo.invalid\",\"password\":\"$(openssl rand -hex 12)\"}")
  [ "${r##* }" = 403 ] && pass "sign-ups closed -> 403" || bad "sign-up not refused: $r"
fi

# 9. Cleanup (also on exit): the user and every row it owns.
cleanup
[ "$(sql -c "select count(*) from public.habits where id = '$HABIT'")" = 0 ] && pass "cleanup cascade" || bad "cleanup left rows"
exit $fail
