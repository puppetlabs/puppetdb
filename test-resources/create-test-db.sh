#!/usr/bin/env bash
createuser -DRSP puppetdb
createuser -dRP --superuser pdb_test_admin
createuser -DRSP pdb_test
createdb -E UTF8 -O puppetdb puppetdb_test
