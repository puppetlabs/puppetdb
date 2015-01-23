---
title: "PuppetDB 2.2 » API » v4 » Querying Aggregate Event Counts"
layout: default
canonical: "/puppetdb/latest/api/query/v4/aggregate-event-counts.html"
---

[event-counts]: ./event-counts.html
[events]: ./events.html
[curl]: ../curl.html
[query]: ./query.html

Puppet agent nodes submit reports after their runs, and the puppet master forwards these to PuppetDB. Each report includes:

* Some data about the entire run
* Some metadata about the report
* Many _events,_ describing what happened during the run

Once this information is stored in PuppetDB, it can be queried in various ways.

* You can query **data about the run** and **report metadata** by making an HTTP request to the [`/reports`](./reports.html) endpoint.
* You can query **data about individual events** by making an HTTP request to the [`/events`][events] endpoint.
* You can query **summaries of event data** by making an HTTP request to the [`/event-counts`][event-counts] or `aggregate-event-counts` endpoints.

## `GET /v4/aggregate-event-counts`

This will return aggregated count information about all of the resource events matching the given query.
This endpoint is built entirely on the [`event-counts`][event-counts] endpoint and will aggregate those
results into a single map.

### URL Parameters

This endpoint builds on top of the [`event-counts`][event-counts] endpoint, and it uses all of the same URL parameters. The supported parameters are re-listed below for reference.

* `query`: Required. A JSON array of query predicates in prefix form (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`).
This query is forwarded to the [`events`][events] endpoint - see there for additional documentation. For general info about queries, see [the page on query structure.][query]

* `summarize_by`: Required. A string specifying which type of object you'd like count. Supported values are
`resource`, `containing_class`, and `certname`.

* `count_by`: Optional. A string specifying what type of object is counted when building up the counts of
`successes`, `failures`, `noops`, and `skips`. Supported values are `resource` (default) and `certname`.

* `counts_filter`: Optional. A JSON array of query predicates in the usual prefix form. This query is applied to
the final event-counts output, but before the results are aggregated. Supported operators are `=`, `>`, `<`,
`>=`, and `<=`. Supported fields are `failures`, `successes`, `noops`, and `skips`.

* `distinct_resources`: Optional.  (EXPERIMENTAL: it is possible that the behavior
of this parameter may change in future releases.)  This parameter is passed along
to the [`event`][events] query - see there for additional documentation.

### Query Operators

This endpoint builds on top of the [`event-counts`][event-counts] and [`events`][events] endpoints, and supports all of the [same operators.](./events.html#query-operators)

### Query Fields

This endpoint builds on top of the [`event-counts`][event-counts] and [`events`][events] endpoints, and supports all of the [same fields.](./events.html#query-fields)

### Response Format

The response is a single JSON map containing aggregated event-count information and a `total` for how many
event-count results were aggregated.

    {
      "successes": 2,
      "failures": 0,
      "noops": 0,
      "skips": 1,
      "total": 3
    }

### Examples

You can use [`curl`][curl] to query information about aggregated resource event counts like so:

    curl -G 'http://localhost:8080/v4/aggregate-event-counts'
            --data-urlencode 'query=["=", "certname", "foo.local"]' \
            --data-urlencode 'summarize_by=containing_class'

## No Paging

This endpoint always returns a single result, so paging is not necessary.

