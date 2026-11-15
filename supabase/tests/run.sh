#!/usr/bin/env bash
# Runs the LoopDo migrations + SQL tests in a throwaway Supabase Postgres container.
# Migrations run as `postgres` (non-superuser, same powers as on Supabase Cloud);
# tests run as `supabase_admin` and switch roles to exercise grants and RLS.
set -euo pipefail
IMG=supabase/postgres:17.11.0.019
NAME=loopdo-sqltest-$$
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=test "$IMG" >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

q() {
  local user=$1; shift
  docker exec -i -e PGOPTIONS='-c client_min_messages=warning' "$NAME" \
    psql -X -q -o /dev/null -U "$user" -d postgres -v ON_ERROR_STOP=1 "$@"
}

# The image runs a temporary server during first-time init, then restarts it:
# wait for the init to finish, then for the real server.
for _ in $(seq 1 120); do
  docker logs "$NAME" 2>&1 | grep -q 'init process complete' && break
  sleep 1
done
for _ in $(seq 1 60); do
  q supabase_admin -tAc "select 1 from auth.users limit 0" >/dev/null 2>&1 && break
  sleep 1
done

for f in "$root"/supabase/migrations/*.sql; do
  echo "migrate $(basename "$f")"
  q postgres < "$f"
done

q supabase_admin < "$here/00_setup.sql"

fail=0
for f in "$here"/[0-9][0-9]_*.sql; do
  [ "$(basename "$f")" = 00_setup.sql ] && continue
  if q supabase_admin < "$f"; then echo "PASS $(basename "$f")"; else echo "FAIL $(basename "$f")"; fail=1; fi
done
exit $fail
