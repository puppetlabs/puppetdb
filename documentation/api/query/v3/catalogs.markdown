---
title: "PuppetDB 1.6 » API » v3 » Querying Catalogs"
layout: default
canonical: "/puppetdb/latest/api/query/v3/catalogs.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[catalog]: ../../wire_format/catalog_format_v1.html

Querying catalogs occurs via an HTTP request to the
`/catalogs` REST endpoint.

## Routes

### `GET /v3/catalogs/<NODE>`

This will return the most recent catalog for the given node.

#### Examples

    curl -X GET http://puppetdb:8080/v3/catalogs/foo.localdomain

    {
      "data" : {
        "name" : "yo.delivery.puppetlabs.net",
        "version" : "e4c339f",
        "transaction-uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
        "edges" : [...],
        "resources" : [...],
      },
      "metadata" : {
        "api_version" : 1
      }
    }

**Note:** the `edges` and `resources` fields above will be populated with data
conforming to the [catalog wire format][catalog].

## Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON map, with a `metadata` key and a `data` key.  The value
of the `data` key is another map, containing the keys `name`, `version`,
`transaction-uuid`, `edges`, and `resources`.  For more details on any of this
data, please refer to the [catalog wire format][catalog].
