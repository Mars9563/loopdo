#!/usr/bin/env bash
# Apply pending LoopDo migrations to the Supabase project.
#
#   ~/.config/loopdo/supabase.env  SUPABASE_DB_URL=<session pooler URI>   (owner-created, mode 600)
#   ~/.config/loopdo/roles.env     LOOPDO_API_PASSWORD, POWERSYNC_PASSWORD (created here on first run)
#
# Secrets are read from those files and passed to psql on stdin, never printed or put in argv.
set -euo pipefail
cfg=${LOOPDO_CONFIG_DIR:-$HOME/.config/loopdo}
root=$(cd "$(dirname "$0")/.." && pwd)

value() { sed -n "s/^$2=//p" "$1" | head -1 | sed "s/^[\"']//; s/[\"']\$//"; }

DB_URL=$(value "$cfg/supabase.env" SUPABASE_DB_URL)
[ -n "$DB_URL" ] || { echo "SUPABASE_DB_URL missing in $cfg/supabase.env" >&2; exit 1; }

roles="$cfg/roles.env"
umask 077
touch "$roles"
new_keys=()
for k in LOOPDO_API_PASSWORD POWERSYNC_PASSWORD LOOPDO_BACKUP_PASSWORD; do
  if ! grep -q "^$k=" "$roles"; then
    echo "$k=$(openssl rand -hex 24)" >> "$roles"
    echo "generated $k in $roles"
    new_keys+=("$k")
  fi
done

run() { PGCONNECT_TIMEOUT=20 PGOPTIONS='-c client_min_messages=warning' psql -X -q -v ON_ERROR_STOP=1 "$DB_URL" "$@"; }

run <<'SQL'
create schema if not exists loopdo_meta;
revoke all on schema loopdo_meta from public;
create table if not exists loopdo_meta.schema_migrations (
  name text primary key,
  applied_at timestamptz not null default now()
);
SQL

applied=$(run -tA -c "select name from loopdo_meta.schema_migrations order by name")
for f in "$root"/supabase/migrations/*.sql; do
  name=$(basename "$f")
  if grep -qx "$name" <<<"$applied"; then
    echo "skip   $name"
    continue
  fi
  { cat "$f"; echo "insert into loopdo_meta.schema_migrations (name) values ('$name');"; } | run -1
  echo "applied $name"
done

# Role passwords: set only when just generated. Re-setting an unchanged password makes a
# new SCRAM hash, and Supabase's pooler keeps the old one cached for minutes, so logins
# (the API's new connections, the nightly backup) fail in between. Seen 2026-11-15.
declare -A role_for=([LOOPDO_API_PASSWORD]=loopdo_api [POWERSYNC_PASSWORD]=powersync_role [LOOPDO_BACKUP_PASSWORD]=loopdo_backup)
if [ ${#new_keys[@]} -eq 0 ]; then
  echo "role passwords unchanged"
else
  for k in "${new_keys[@]}"; do
    pw=$(value "$roles" "$k")
    run <<SQL
alter role ${role_for[$k]} password '$pw';
SQL
    echo "password set for ${role_for[$k]}"
  done
fi
