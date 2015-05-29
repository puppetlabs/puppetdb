---
title: "PuppetDB 2.3 » Meta API » v1 » Querying Server Time"
layout: default
canonical: "/puppetdb/latest/api/meta/v1/server-time.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[query]: ./query.html

The `/server-time` endpoint can be used to retrieve the server time from the PuppetDB server.


## `GET /pdb/meta/v1/server-time`

This query endpoint will return the current time of the clock on the PuppetDB
server.  This can be useful as input to other time-based queries against PuppetDB,
to eliminate the possibility of time differences between the clocks on client
machines.

This endpoint does not use any URL parameters or query strings.

### Response Format

The response will be in `application/json`, and will return a JSON map with a
single key: `server_time`, whose value is an ISO-8601 representation of the
current time on the PuppetDB server.

    {"server_time": "2013-09-20T20:54:27.472Z"}

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/meta/v1/server-time

    {"server_time": "2013-09-20T20:54:27.472Z"}

