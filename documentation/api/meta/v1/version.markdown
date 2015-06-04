---
title: "PuppetDB 3.0 » Metadata API » v1 » Querying PuppetDB Version"
layout: default
canonical: "/puppetdb/latest/api/meta/v1/version.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

The `/version` endpoint can be used to retrieve version information from the PuppetDB server.


## `GET /pdb/meta/v1/version`

This query endpoint will return version information about the running PuppetDB
server.

This endpoint does not use any URL parameters or query strings.

### Response Format

The response will be in `application/json`, and will return a JSON map with a
single key: `version`, whose value is a string representation of the version
of the running PuppetDB server.

    {"version": "X.Y.Z"}

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/meta/v1/version

    {"version": "X.Y.Z"}
