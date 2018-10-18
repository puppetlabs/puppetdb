#!/usr/bin/env bash

set -x
set -e

PSQL="psql -U postgres"
POSTGRES_DB="${POSTGRES_DB:-puppetdb}"

$PSQL "$POSTGRES_DB" -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
$PSQL "$POSTGRES_DB" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
