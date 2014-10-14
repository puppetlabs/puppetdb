---
title: "PuppetDB 2.2 » API » v4 » Querying Catalogs"
layout: default
canonical: "/puppetdb/latest/api/query/v4/catalogs.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[catalog]: ../../wire_format/catalog_format_v4.html
[query]: ./query.html

You can query catalogs by making an HTTP request to the
`/catalogs` endpoint.

> **Note:** The v4 API is experimental and may change without notice. For stability, it is recommended that you use the v3 API instead.

## `GET /v4/catalogs/<NODE>`

This will return the most recent catalog for the given node.

This endpoint does not use any URL parameters or query strings.

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON map with the following fields:
* `name`: the certname associated with the catalog
* `version`: an arbitrary string that uniquely identifies each catalog for a
  node
* `environment`: the environment associated with the catalog's certname
* `transaction-uuid`: a string used to tie a catalog to a report from the same
  puppet run
* `producer-timestamp`: a string representing the time at which the
  `replace-catalog` command for a given catalog was submitted from the master.
  Generation of this field will be pushed back to the agent in a later release, so it
  should not be relied on in its current form.
* `resources`: a list of resource objects, containing every resource in the
  catalog
* `edges`: a list of edge objects, representing relationships between
  resources

**Note**: For more details refer to the [catalog wire format][catalog].

### Examples

    curl -X GET http://puppetdb:8080/v4/catalogs/foo.localdomain

    {
      "name" : "yo.delivery.puppetlabs.net",
      "version" : "e4c339f",
      "transaction-uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
      "producer-timestamp": "2014-10-13T20:46:00.000Z",
      "environment" : "production",
      "edges" : [...],
      "resources" : [...]
    }

## No Paging

This endpoint always returns a single result, so paging is not necessary.
