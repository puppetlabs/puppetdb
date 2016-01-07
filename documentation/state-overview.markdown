---
title: "PE PuppetDB 3.2 » Querying State Overview"
layout: default
canonical: "/puppetdb/latest/state-overview.html"
---

[reports]: /puppetdb/latest/api/query/v4/reports.html
[nodes]: /puppetdb/latest/api/query/v4/nodes.html
[curl]: /puppetdb/latest/api/query/curl.html

## `GET /v1/state-overview`

This will return aggregated count information about all of the report statuses in PuppetDB.
This endpoint is built entirely on the [`reports`][reports] and [`nodes`][nodes] endpoints
and will aggregate results from queries to those endpoints into a single map.

### URL Parameters

* `unresponsive_threshold`: Optional. This parameter is used to filter on which nodes are _unresponsive_. All nodes that hve not submitted a reprot within the threshold limit will be marked as unresponsive. The value supplied for this parameter is an integer whose units are implicitly seconds. By default a node will be marked as unresponsive it has not submitted a report within the hour, which is equivalent to setting `unresponsive_threshold=3600`.

### Query Operators and Fields

This endpoint builds on top of the [`reports`][reports] and [`nodes`][nodes] endpoints. Querying this endpoint is not supported, but all the same information is available via the aforementioned endpoints.

### Response Format

The response is a single JSON map containing aggregated report status count information.

    {
        "unresponsive": 2,
        "changed": 0,
        "noop": 0,
        "unchanged": 1,
        "unreported": 3
        "failed": 4
    }

### Examples

You can use [`curl`][curl] to query information about report status counts like so:

    curl -G 'http://localhost:8080/pe/v1/state-overview'

## No Paging

This endpoint always returns a single result, so paging is not necessary.
