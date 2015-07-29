---
title: "PuppetDB 3.0 » API » v4 » Querying Event Counts"
layout: default
canonical: "/puppetdb/latest/api/query/v4/event-counts.html"
---

[events]: ./events.html
[paging]: ./paging.html
[curl]: ../curl.html
[query]: ./query.html

> **Experimental Endpoint**: The event-counts endpoint is designated
> as experimental. It may be altered or removed in a future release.

Puppet agent nodes submit reports after their runs, and the puppet master forwards these to PuppetDB. Each report includes:

* Some data about the entire run
* Some metadata about the report
* Many _events,_ describing what happened during the run

Once this information is stored in PuppetDB, it can be queried in various ways.

* You can query **data about the run** and **report metadata** by making an HTTP request to the [`/reports`](./reports.html) endpoint.
* You can query **data about individual events** by making an HTTP request to the [`/events`][events] endpoint.
* You can query **summaries of event data** by making an HTTP request to the `/event-counts` or [`aggregate-event-counts`](./aggregate-event-counts.html) endpoints.

## `GET /pdb/query/v4/event-counts`

This will return count information about all of the resource events matching the given query.
For a given object type (resource, containing_class, or node), you can retrieve counts of the
number of events on objects of that type that had a status of `success`, `failure`, `noop`,
or `skip`.

See the [`events`][events] endpoint for additional documentation as this endpoint builds heavily on it.

### URL Parameters

* `query`: Optional. A JSON array of query predicates in prefix form (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`).
This query is forwarded to the [`events`][events] endpoint - see there for additional documentation. For general info about queries, see [the page on query structure.][query]

* `summarize_by`: Required. A string specifying which type of object you'd like to see counts for.
Supported values are `resource`, `containing_class`, and `certname`.

* `count_by`: Optional. A string specifying what type of object is counted when building up the
counts of `successes`, `failures`, `noops`, and `skips`. Supported values are `resource` (default)
and `certname`.

* `counts_filter`: Optional. A JSON array of query predicates in the usual prefix form. This query
is applied to the final event counts output. Supported operators are `=`, `>`, `<`, `>=`, and `<=`.
Supported fields are `failures`, `successes`, `noops`, and `skips`.

* `distinct_resources`: Optional.  (EXPERIMENTAL: it is possible that the behavior
of this parameter may change in future releases.)  This parameter is passed along
to the [`events`][events] query - see there for additional documentation.

### Query Operators

This endpoint builds on top of the [`events`][events] endpoint, and supports all of the [same operators.](./events.html#query-operators)

### Query Fields

This endpoint builds on top of the [`events`][events] endpoint, and supports all of the [same fields.](./events.html#query-fields)

### Response Format

The response is a JSON array of maps. Each map contains the counts of events that matched the input
parameters. The events are counted based on their statuses: `failures`, `successes`, `noops`, `skips`.

The maps also contain additional data about which object the events occurred on. The `subject_type`
is the value that was used to summarize by (and therefore should match the input value to `summarize_by`).
The `subject` map contains specific data about the object the event occurred on, and will vary based on
the value specified for `summarize_by`.

When summarizing by `certname`, the `subject` will contain a `title` key:

    [
      {
        "subject_type": "certname",
        "subject": { "title": "foo.local" },
        "failures": 0,
        "successes": 2,
        "noops": 0,
        "skips": 1
      },
      {
        "subject_type": "certname",
        "subject": { "title": "bar.local" },
        "failures": 1,
        "successes": 0,
        "noops": 0,
        "skips": 1
      }
    ]

When summarizing by `resource`, the `subject` will contain a `type` and `title` key:

    [
      {
        "subject_type": "resource",
        "subject": { "type": "Notify", "title": "Foo happened" },
        "failures": 0,
        "successes": 1,
        "noops": 0,
        "skips": 0
      },
      {
        "subject_type": "resource",
        "subject": { "type": "Notify", "title": "Bar happened" },
        "failures": 0,
        "successes": 0,
        "noops": 0,
        "skips": 1
      }
    ]

When summarizing by `containing_class`, the `subject` will contain a `title` key:

    [
      {
        "subject_type": "containing_class",
        "subject": { "title": "Foo::Class" },
        "failures": 1,
        "successes": 2,
        "noops": 0,
        "skips": 1
      },
      {
        "subject_type": "containing_class",
        "subject": { "title": null },
        "failures": 0,
        "successes": 0,
        "noops": 2,
        "skips": 0
      }
    ]

### Examples

You can use [`curl`][curl] to query information about resource event counts like so:

    curl -G 'http://localhost:8080/pdb/query/v4/event-counts' \
      --data-urlencode 'query=["=", "certname", "foo.local"]' \
      --data-urlencode 'summarize_by=resource' \
      --data-urlencode 'count_by=certname' \
      --data-urlencode 'counts_filter=[">", "failures", 0]'

## Paging

This endpoint supports paged results via the common PuppetDB paging URL parameters.
For more information, please see the documentation on [paging][paging].
