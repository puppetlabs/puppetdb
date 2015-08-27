#!/usr/bin/env bash
createuser -DRSP puppetdb
createdb -E UTF8 -O puppetdb puppetdb_test
