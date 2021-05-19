---
title: "Catalogs endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/catalogs.html"
---
# Catalogs endpoint

[catalog]: ../../wire_format/catalog_format_v4.markdown
[curl]: ../curl.markdown#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.markdown
[query]: query.markdown
[subqueries]: ./ast.markdown#subquery-operators
[ast]: ./ast.markdown
[edges]: ./edges.markdown
[environments]: ./environments.markdown
[producers]: ./producers.markdown
[nodes]: ./nodes.markdown
[resources]: ./resources.markdown
[rich_data]: query.markdown#rich-data

You can query catalogs by making an HTTP request to the
`/catalogs` endpoint.

## `/pdb/query/v4/catalogs`

This will return a JSON array containing the most recent catalog for each node
in your infrastructure.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [our guide to query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query operators

See [the AST query language page][ast].

### Query fields

* `certname` (string): the certname associated with the catalog.
* `version` (string): an arbitrary string that uniquely identifies each catalog for a node.
* `environment` (string): the environment associated with the catalog's certname.
* `transaction_uuid` (string): a string used to tie a catalog to a report from
  the same Puppet run (use `catalog_uuid` when running off a cached catalog).
* `catalog_uuid` (string): a string used to tie a catalog to a report to the
  catalog used from that Puppet run.
* `code_id` (string): a string used to tie a catalog to the Puppet code which generated the catalog.
* `hash` (string): SHA-1 hash of the resources of associated with a node's most
  recent catalog.
* `producer_timestamp` (string): a string representing the time at which the
  `replace_catalog` command for a given catalog was submitted from the Puppet Server.
* `producer` (string): the certname of the Puppet Server that sent the catalog to PuppetDB.

### Subquery relationships

The following list contains related entities that can be used to constrain the
result set using implicit subqueries. For more information consult the
documentation for [subqueries][subqueries].

* [`producers`][producers]: the Puppet Server that sent the catalog to PuppetDB.
* [`environments`][environments]: environment for a catalog.

### Response format

Successful responses will be in `application/json`.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "certname" : <node certname>,
      "version" : <catalog version>,
      "environment" : <catalog environment>,
      "hash" : <sha1 sum of catalog resources>,
      "transaction_uuid" : <string to identify puppet run>,
      "code_id" : <string to identify puppet code>,
      "producer_timestamp": <time of transmission by Puppet Server>,
      "producer": <Puppet Server certname>
      "resources" : <expanded resources>,
      "edges" : <expanded edges>
    }

The `<expanded resources>` object is of the following form:

    {
      "href": <url>,
      "data": [ {
        "certname": <string>,
        "resource": <string>,
        "type": <string>,
        "title": <sttring>,
        "exported": <boolean>,
        "tags": [<tags>, ...],
        "file": <string>,
        "line": <number>,
        "parameters": <any>
      } ... ]
    }

In `parameters` map, any [rich data][rich_data] values will appear as
readable strings.

The `<expanded edges>` object is of the follow form:

    {
      "href": <url>,
      "data": [ {
        "relationship": <string>,
        "source_title": <string>,
        "source_type": <string>,
        "target_title": <string>,
        "target_type": <string>
      } ... ]
    }

### Examples

This query will return the complete list of catalogs:

    curl -X GET http://localhost:8080/pdb/query/v4/catalogs

    [ {
      "certname" : "yo.delivery.puppetlabs.net",
      "hash" : "62cdc40a78750144b1e1ee06638ac2dd0eeb9a46",
      "version" : "e4c339f",
      "transaction_uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
      "code_id" : null,
      "producer_timestamp": "2014-10-13T20:46:00.000Z",
      "producer": "dad.puppetlabs.net",
      "environment" : "production",
      "edges" : {...},
      "resources" : {...}
    },
    {
      "certname" : "foo.delivery.puppetlabs.net",
      "hash" : "e1a4610ecbb3483fa5e637f42374b2cc46d06474",
      "version" : "449720",
      "transaction_uuid" : "9a3c8da6-f48c-4567-b24e-ddae5f80a6c6",
      "code_id" : null,
      "producer_timestamp": "2014-11-20T02:15:20.861Z",
      "producer": "mom.puppetlabs.net",
      "environment" : "production",
      "edges" : {...},
      "resources" : {...}
    } ]

This query will return all catalogs with producer_timestamp after 2014-11-19:

    curl -X GET http://localhost:8080/pdb/query/v4/catalogs \
      --data-urlencode 'query=[">","producer_timestamp","2014-11-19"]'

    [ {
      "certname" : "foo.delivery.puppetlabs.net",
      "hash" : "e1a4610ecbb3483fa5e637f42374b2cc46d06474",
      "version" : "449720",
      "transaction_uuid" : "9a3c8da6-f48c-4567-b24e-ddae5f80a6c6",
      "code_id" : null,
      "producer_timestamp": "2014-11-20T02:15:20.861Z",
      "producer": "mom.puppetlabs.net",
      "environment" : "production",
      "edges" : {...},
      "resources" : {...}
    } ]

## `/pdb/query/v4/catalogs/<NODE>`

This will return the most recent catalog for the given node. Supplying a node
this way will restrict any given query to only apply to that node, but in
practice this endpoint is typically used without a query string or URL
parameters.

The result will be a single map of the catalog structure described above, or
a JSON error message if the catalog is not found.

### Examples

    curl -X GET http://localhost:8080/pdb/query/v4/catalogs/foo.localdomain

    {
     "certname" : "yo.delivery.puppetlabs.net",
     "hash" : "62cdc40a78750144b1e1ee06638ac2dd0eeb9a46",
     "version" : "e4c339f",
     "transaction_uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
     "code_id" : null,
     "catalog_uuid" : null,
     "producer_timestamp": "2014-10-13T20:46:00.000Z",
     "producer": "dad.puppetlabs.net",
     "environment" : "production",
     "edges" : {...},
     "resources" : {...}
    }

    curl -X GET http://localhost:8080/pdb/query/v4/catalogs/my_fake_hostname

    {
      "error" : "Could not find catalog for my_fake_hostname"
    }

## `/pdb/query/v4/catalogs/<NODE>/edges`

This will return all edges for a particular catalog, designated by a node certname.

This is a shortcut to the [`/edges`][edges] endpoint. It behaves the same as a
call to [`/edges`][edges] with a query string of `["=", "certname", "<NODE>"]`,
except results are returned even if the node is deactivated or expired.

### URL parameters / query operators / query fields / response format

This route is an extension of the [`edges`][edges] endpoint. It uses the same
parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which
will be used to return a subset of the information normally returned by this
route.

## `/pdb/query/v4/catalogs/<NODE>/resources`

This will return all resources for a particular catalog, designated by a node
certname.

This is a shortcut to the [`/resources`][resources] endpoint. It behaves the
same as a call to [`/resources`][resources] with a query string of `["=",
"certname", "<NODE>"]`, except results are returned even if the node is
deactivated or expired.

### URL parameters / query operators / query fields / response format

This route is an extension of the [`/resources`][resources] endpoint. It uses
the exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which
will be used to return a subset of the information normally returned by this
route.

### URL Parameters / Query Operators / Query Fields / Response Format

This route is an extension of the `/pdb/query/v4/catalogs` endpoint. It uses the
exact same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which
will be used to return a subset of the information normally returned by this
route.

### Subquery Relationships

Here is a list of related entities that can be used to constrain the result set
using implicit subqueries. For more information consult the documentation for
[subqueries][subqueries].

* [`nodes`][nodes]: Node for a catalog.
* [`environments`][environments]: Environment for a catalog.

## Paging

The v4 catalogs endpoint supports all the usual paging URL parameters described
in the documents on [paging][paging]. Ordering is allowed on every queryable
field.
