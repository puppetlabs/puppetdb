---
title: "Event counts endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/event-counts.html"
---

# Event counts endpoint

[events]: ./events.html
[paging]: ./paging.html
[curl]: ../curl.html
[query]: ./query.html

> **Experimental endpoint**: The event-counts endpoint is designated
> as experimental. It may be altered or removed in a future release.

Puppet agent nodes submit reports after their runs, and the Puppet Server forwards these to PuppetDB. Each report includes:

* Data about the entire run
* Metadata about the report
* Many _events,_ describing what happened during the run

After this information is stored in PuppetDB, it can be queried in various ways.

* You can query **data about the run** and **report metadata** by making an HTTP request to the [`/reports`](./reports.html) endpoint.
* You can query **data about individual events** by making an HTTP request to the [`/events`][events] endpoint.
* You can query **summaries of event data** by making an HTTP request to the `/event-counts` or [`aggregate-event-counts`](./aggregate-event-counts.html) endpoints.

## `/pdb/query/v4/event-counts`

Returns count information about all of the resource events matching the given query.
For a given object type (resource, containing_class, or node), you can retrieve counts of the
number of events on objects of that type that had a status of `success`, `failure`, `noop`, or `skip`.

See the [`events`][events] endpoint for additional documentation, as this endpoint builds heavily on it.

### URL parameters

* `query`: optional. A JSON array of query predicates in prefix form (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`).
This query is forwarded to the [`events`] endpoint; additional information about this endpoint can be found [here][events]. For general info about queries, see [our guide to query structure][query].

* `summarize_by`: required. A string specifying which type of object you'd like to see counts for. Supported values are `resource`, `containing_class`, and `certname`.

* `count_by`: optional. A string specifying what type of object is counted when building up the counts of `successes`, `failures`, `noops`, and `skips`. Supported values are `resource` (default) and `certname`.

* `counts_filter`: optional. A JSON array of query predicates in the usual prefix form. This query
is applied to the final event counts output. Supported operators are `=`, `>`, `<`, `>=`, and `<=`.
Supported fields are `failures`, `successes`, `noops`, and `skips`.

* `distinct_resources`: optional. (**Experimental: it is possible that the behavior
of this parameter may change in future releases.**) This parameter is passed along
to the `events` query. See the [`events` documentation][events] for more information.

### Query operators

This endpoint builds on top of the [`events`][events] endpoint, and supports all of the [same operators.](./events.html#query-operators)

### Query fields

This endpoint builds on top of the [`events`][events] endpoint, and supports all of the [same fields.](./events.html#query-fields)

### Response format

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

When summarizing by `resource`, the `subject` will contain `type` and `title` keys:

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

#### Puppet Enterprise

In PE, the `successes` and `noops` counts are subdivided into intentional and corrective parts.
Events are mapped to the corresponding counts based on the value of `corrective_change` flag.

    [
      {
        "subject_type": "certname",
        "subject": { "title": "foo.local" },
        "failures": 0,
        "intentional_successes": 2,
        "corrective_successes": 0,
        "intentional_noops": 0,
        "corrective_noops": 0,
        "skips": 1
      },
      {
        "subject_type": "certname",
        "subject": { "title": "bar.local" },
        "failures": 1,
        "intentional_successes": 0,
        "corrective_successes": 0,
        "intentional_noops": 0,
        "corrective_noops": 0,
        "skips": 1
      }
    ]

`intentional_successes`, `corrective_successes`, `intentional_noops`, and `corrective_noops` fields
can be used in `counts_filter` too.

### Examples

You can use [`curl`][curl] to query information about resource event counts:

    curl -G 'http://localhost:8080/pdb/query/v4/event-counts' \
      --data-urlencode 'query=["=", "certname", "foo.local"]' \
      --data-urlencode 'summarize_by=resource' \
      --data-urlencode 'count_by=certname' \
      --data-urlencode 'counts_filter=[">", "failures", 0]'

## Paging

This endpoint supports paged results via the common PuppetDB paging URL parameters. For more information, please see the documentation on [paging][paging].
