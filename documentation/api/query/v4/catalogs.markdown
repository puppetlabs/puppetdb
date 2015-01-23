---
title: "PuppetDB 2.2 » API » v4 » Querying Catalogs"
layout: default
canonical: "/puppetdb/latest/api/query/v4/catalogs.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[catalog]: ../../wire_format/catalog_format_v4.html
[paging]: ./paging.html
[query]: ./query.html

You can query catalogs by making an HTTP request to the
`/catalogs` endpoint.

## `GET /v4/catalogs`

This will return a JSON array containing the most recent catalog for each node in your infrastructure.

### URL Parameters
* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

* `name` (string): the certname associated with the catalog
* `version` (string): an arbitrary string that uniquely identifies each catalog for a node
* `environment` (string): the environment associated with the catalog's certname
* `transaction_uuid` (string): a string used to tie a catalog to a report from the same puppet run
* `hash` (string): sha1 hash of the resources of associated with a node's most
  recent catalog
* `producer_timestamp` (string): a string representing the time at which the
  `replace_catalog` command for a given catalog was submitted from the master.
  Generation of this field will be pushed back to the agent in a later release, so it
  should not be relied on in its current form.

### Response Format

Successful responses will be in `application/json`.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "name" : <node name>,
      "version" : <catalog version>,
      "environment" : <catalog environment>,
      "hash" : <sha1 sum of catalog resources>,
      "transaction_uuid" : <string to identify puppet run>,
      "producer_timestamp": <time of transmission by master>,
      "resources" : <list of objects representing resources in the catalog>,
      "edges" : <list of objects representing relationships between resources>
    }

**Note**: For more details refer to the [catalog wire format][catalog].

### Examples

This query will return the complete list of catalogs:

    curl -X GET http://puppetdb:8080/v4/catalogs

    [ {
      "name" : "yo.delivery.puppetlabs.net",
      "hash" : "62cdc40a78750144b1e1ee06638ac2dd0eeb9a46",
      "version" : "e4c339f",
      "transaction_uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
      "producer_timestamp": "2014-10-13T20:46:00.000Z",
      "environment" : "production",
      "edges" : [...],
      "resources" : [...]
    },
    {
      "name" : "foo.delivery.puppetlabs.net",
      "hash" : "e1a4610ecbb3483fa5e637f42374b2cc46d06474",
      "version" : "449720",
      "transaction_uuid" : "9a3c8da6-f48c-4567-b24e-ddae5f80a6c6",
      "producer_timestamp": "2014-11-20T02:15:20.861Z",
      "environment" : "production",
      "edges" : [...],
      "resources" : [...]
    } ]

This query will return all catalogs with producer_timestamp after 2014-11-19:

    curl -X GET http://puppetdb:8080/v4/catalogs --data-urlencode 'query=[">","producer_timestamp","2014-11-19"]'

    [ {
      "name" : "foo.delivery.puppetlabs.net",
      "hash" : "e1a4610ecbb3483fa5e637f42374b2cc46d06474",
      "version" : "449720",
      "transaction_uuid" : "9a3c8da6-f48c-4567-b24e-ddae5f80a6c6",
      "producer_timestamp": "2014-11-20T02:15:20.861Z",
      "environment" : "production",
      "edges" : [...],
      "resources" : [...]
    } ]


## `GET /v4/catalogs/<NODE>`

This will return the most recent catalog for the given node. Supplying a node
this way will restrict any given query to only apply to that node, but in
practive this endpoint is typically used without a query string or URL
parameters.

The result will be a single map of the catalog structure described above, or
a JSON error message if the catalog is not found:

### Examples

    curl -X GET http://puppetdb:8080/v4/catalogs/foo.localdomain

    {
     "name" : "yo.delivery.puppetlabs.net",
     "hash" : "62cdc40a78750144b1e1ee06638ac2dd0eeb9a46",
     "version" : "e4c339f",
     "transaction_uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
     "producer_timestamp": "2014-10-13T20:46:00.000Z",
     "environment" : "production",
     "edges" : [...],
     "resources" : [...]
    }

    curl -X GET http://puppetdb:8080/v4/catalogs/my_fake_hostname

    {
      "error" : "Could not find catalog for my_fake_hostname"
    }


## Paging

The v4 catalogs endpoint supports all the usual paging URL parameters described
in the documents on [paging][paging]. Ordering is allowed on every queryable
field.
