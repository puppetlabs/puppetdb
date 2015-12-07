---
title: "PuppetDB 3.2 » API » v4 » Root Endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/index.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[from]: ./operators.html#context-operators
[entities]: ./entities.html

*Note:* This endpoint is experimental. It may be altered or removed in a future release.

The root query endpoint can be used to retrieve any known entities, from a
single endpoint.

## `/pdb/query/v4`

This will return any known entity based on the required `query` field. Unlike
other endpoints, the [entity][entities] must be supplied using a query with the [`from`][from]
operator.

### URL Parameters

* `query`: Required. A JSON array containing the query in prefix notation
(`["from", "<ENTITY>", ["<OPERATOR>", "<FIELD>", "<VALUE>"]]`). Unlike other endpoints,
a query with a [`from`][from] is required to choose the [entity][entities] to query for. For
general info about queries, see [the page on query structure.][query]

### Response Format

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
        "report_environment": "production",
        "report_timestamp": "2015-11-23T19:25:23.394Z"
      }
    ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].

