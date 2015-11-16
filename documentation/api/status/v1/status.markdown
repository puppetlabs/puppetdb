---
title: "PuppetDB 3.2 » Status API » v1 » Querying PuppetDB Status"
layout: default
canonical: "/puppetdb/latest/api/status/v1/status.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[status-api]: https://docs.puppetlabs.com/pe/latest/status_api.html

The `/status` endpoint implements the Puppet Labs Status API for coordinated
monitoring of Puppet Labs services. See the [central documentation][status-api]
for detailed information.

## `/status/v1/services/puppetdb-status`

This query endpoint will return status about the PuppetDB instance on a host.

### Response Format

The response will be in `application/json`, and will return a JSON map like the
following:

    {
        "detail_level": "info",
         "service_status_version": 1,
         "service_version": "4.0.0-SNAPSHOT",
         "state": "running",
         "status": {
             "maintenance_mode?": false,
             "read_db_up?": true,
             "write_db_up?": true
         }
    }

* `detail_level`: info is currently the only level
* `service_status_version`: version of the status API
* `service_version`: version of PuppetDB
* `state`: "running" if read and write databases are up, "error" if down
* `status`:
    * `maintenance_mode?`: indicates whether PuppetDB is in maintenance mode.
    PuppetDB enters maintenance mode at startup and exits it after completing any
    pending migrations. While in maintenance mode PuppetDB will not respond to
    queries.
    * `read_db_up?`: indicates whether the read database is responding to queries
    * `write_db_up?`: indicates whether the write database is responding to queries
