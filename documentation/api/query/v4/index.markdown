---
title: "Root endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/index.html"
---
# Root endpoint

[curl]: ../curl.markdown#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.markdown
[query]: query.markdown
[from]: ./ast.markdown#context-operators
[entities]: ./entities.markdown
[pql]: ./pql.markdown
[ast]: ./ast.markdown

The root query endpoint can be used to retrieve any known entities from a
single endpoint.

## `/pdb/query/v4`

This will return any known entity based on the required `query` field. Unlike
other endpoints, the [entity][entities] must be supplied using a query with the [`from`][from]
operator or a [PQL][pql] query string.

Like all other PDB query endpoints, query results from the root query endpoint
will be restricted to active nodes by default. To target only inactive nodes,
you can specify `node_state = 'inactive'`; for all both active and inactive, use
`node_state = 'any'`.

### URL parameters

* `query`: required. Either a [PQL query string][pql], or an [AST][ast] JSON array containing the query in prefix notation
(`["from", "<ENTITY>", ["<OPERATOR>", "<FIELD>", "<VALUE>"]]`). Unlike other endpoints,
a query with a [`from`][from] is required to choose the [entity][entities] for which to query. For
general info about queries, see [our guide to query structure.][query]

* `ast_only`: optional. A boolean value. When true, the query response will be the supplied 
`query` in AST, either exactly as supplied or translated from PQL. False by default.

### Response format

The response will be in `application/json`, and will contain a list of JSON
object results based on the [entity][entities] provided in the top-level [`from`][from] query.

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/query/v4 --data-urlencode 'query=["from","nodes",["=","certname","macbook-pro.local"]]'

    [
      {
        "catalog_environment": "production",
        "catalog_timestamp": "2015-11-23T19:25:25.561Z",
        "certname": "macbook-pro.local",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2015-11-23T19:25:25.079Z",
        "latest_report_hash": "0b2aa3bbb1deb71a5328c1d934eadbba5f52d733",
        "latest_report_status": "unchanged",
        "latest_report_noop": true,
        "cached_catalog_status": "not_used",
        "report_environment": "production",
        "report_timestamp": "2015-11-23T19:25:23.394Z"
      }
    ]

The same query can also be executed using [PQL][pql]:

    curl -X GET http://localhost:8080/pdb/query/v4 --data-urlencode 'query=nodes { certname = "macbook-pro.local" }'

    [
      {
        "catalog_environment": "production",
        "catalog_timestamp": "2015-11-23T19:25:25.561Z",
        "certname": "macbook-pro.local",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2015-11-23T19:25:25.079Z",
        "latest_report_hash": "0b2aa3bbb1deb71a5328c1d934eadbba5f52d733",
        "latest_report_status": "unchanged",
        "latest_report_noop": true,
        "cached_catalog_status": "not_used",
        "report_environment": "production",
        "report_timestamp": "2015-11-23T19:25:23.394Z"
      }
    ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, see the documentation
on [paging][paging].

