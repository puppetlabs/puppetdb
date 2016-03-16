#!/usr/bin/env bash
createdb -E UTF8 -O puppetdb puppetdb
psql -c "create extension pg_trgm; create extension pg_stat_statements; create extension pgcrypto" -d puppetdb
createdb -E UTF8 -O puppetdb puppetdb_rbac
psql -c "create extension citext" -d puppetdb_rbac
createdb -E UTF8 -O puppetdb puppetdb_activity
