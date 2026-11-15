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
if [ ! -f "$roles" ]; then
  umask 077
  {
    echo "LOOPDO_API_PASSWORD=$(openssl rand -hex 24)"
    echo "POWERSYNC_PASSWORD=$(openssl rand -hex 24)"
  } > "$roles"
  echo "generated role passwords in $roles"
fi

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

# Role passwords (idempotent; via stdin so they never show in process lists).
api_pw=$(value "$roles" LOOPDO_API_PASSWORD)
ps_pw=$(value "$roles" POWERSYNC_PASSWORD)
run <<SQL
alter role loopdo_api password '$api_pw';
alter role powersync_role password '$ps_pw';
SQL
echo "role passwords set"
