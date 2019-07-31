---
title: "Server time endpoint"
layout: default
canonical: "/puppetdb/latest/api/meta/v1/server-time.html"
---

[curl]: ../../query/curl.html#using-curl-from-localhost-non-sslhttp

The `/server-time` endpoint can be used to retrieve the server time from the PuppetDB server.

## `/pdb/meta/v1/server-time`

This metadata endpoint will return the current time of the clock on the PuppetDB
server. This can be useful as input to other time-based queries against PuppetDB,
to eliminate the possibility of time differences between the clocks on client
machines.

This endpoint does not use any URL parameters or query strings.

### Response format

The response will be in `application/json`, and will return a JSON map with a
single key: `server_time`, whose value is an ISO-8601 representation of the
current time on the PuppetDB server.

    {"server_time": "2013-09-20T20:54:27.472Z"}

### Example

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/meta/v1/server-time

    {"server_time": "2013-09-20T20:54:27.472Z"}

