---
title: "Version endpoint"
layout: default
canonical: "/puppetdb/latest/api/meta/v1/version.html"
---

# Version endpoint

[curl]: ../../query/curl.markdown#using-curl-from-localhost-non-sslhttp

The `/version` endpoint can be used to retrieve version information from the PuppetDB server.

## `/pdb/meta/v1/version`

This query endpoint will return version information about the running PuppetDB
server.

This endpoint does not use any URL parameters or query strings.

## `/pdb/meta/v1/version/latest`

This query will display a message describing the latest version of PuppetDB.

### Response format

The response will be in `application/json`, and will return a JSON map with a
single key: `version`, whose value is a string representation of the version
of the running PuppetDB server.

    {"version": "X.Y.Z"}

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/meta/v1/version

    {"version": "X.Y.Z"}

    curl -X GET http://localhost:8080/pdb/meta/v1/version/latest

    {
      "newer" : false,
      "product" : "puppetdb",
      "link" : "https://docs.puppetlabs.com/puppetdb/2.3/release_notes.markdown",
      "message" : "Version 2.3.4 is now available!",
      "version" : "2.3.4"
    }
