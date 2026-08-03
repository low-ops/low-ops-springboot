#!/bin/sh
set -e

if [ -n "$POSTGRES_HOST" ] && [ -n "$POSTGRES_DATABASE" ] && [ -n "$POSTGRES_USER" ] && [ -n "$POSTGRES_PASSWORD" ]; then
  echo "[INFO] PostgreSQL env vars detected. Schema will be applied on application startup."
else
  echo "[WARNING] POSTGRES_* env vars not set. Skipping database setup and using in-memory fallback."
fi

exec "$@"
