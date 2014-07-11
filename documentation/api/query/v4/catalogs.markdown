---
title: "PuppetDB 2.1 » API » v4 » Querying Catalogs"
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

The result will be a JSON map, with a `metadata` key and a `data` key.  The value
of the `data` key is another map, containing the keys `name`, `version`,
`transaction-uuid`, `edges`, and `resources`.  For more details on any of this
data, please refer to the [catalog wire format][catalog].

### Examples

    curl -X GET http://puppetdb:8080/v4/catalogs/foo.localdomain

    {
      "name" : "yo.delivery.puppetlabs.net",
      "version" : "e4c339f",
      "transaction-uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
      "environment" : "production",
      "edges" : [...],
      "resources" : [...],
    }

**Note:** the `edges` and `resources` fields above will be populated with data
conforming to the [catalog wire format][catalog].

## No Paging

This endpoint always returns a single result, so paging is not necessary.
