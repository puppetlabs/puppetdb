---
title: "PuppetDB: Events endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/events.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[report]: ./reports.html
[ast]: ./ast.html
[paging]: ./paging.html
[query]: ./query.html
[8601]: http://en.wikipedia.org/wiki/ISO_8601
[subqueries]: ./ast.html#subquery-operators
[environments]: ./environments.html
[nodes]: ./nodes.html
[reports]: ./reports.html
[rich_data]: ./query.html#rich-data

Puppet agent nodes submit reports after their runs, and the Puppet master forwards these to PuppetDB. Each report includes:

* Data about the entire run
* Metadata about the report
* Many _events,_ describing what happened during the run

After this information is stored in PuppetDB, it can be queried in various ways.

* You can query **data about the run** and **report metadata** by making an HTTP request to the [`/reports`][report] endpoint.
* You can query **data about individual events** by making an HTTP request to the `/events` endpoint.
* You can query **summaries of event data** by making an HTTP request to the [`/event-counts`](./event-counts.html) or [`aggregate-event-counts`](./aggregate-event-counts.html) endpoints.

## `/pdb/query/v4/events`

Returns all resource events matching the given query. (Resource events
are generated from Puppet reports.)

### URL parameters

* `query`: optional. A JSON array of query predicates, in prefix form (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query] If `query` is omitted, all events are returned.

* `distinct_resources`: optional. Boolean. (For example, `distinct_resources=true`.) (EXPERIMENTAL: it is possible that the behavior of this parameter may change in future releases.) If specified, the result set will only return the most recent event for a given resource on a given node.

    For example: if the resource `File[/tmp/foo]` was failing on some node
    but has since been fixed and is now succeeding, then a "normal" event query might
    return both the success and failure events. A query with `distinct_resources=true`
    would only return the success event, because this is the most recent event for that resource.

    Because a `distinct_resources` query can be expensive, it requires a limited
    window of time to examine. Use the `distinct_start_time` and
    `distinct_end_time` parameters to define this interval.
    Issuing a `distinct_resources` query without specifying both of these parameters will cause an error.

* `distinct_start_time`: used with `distinct_resources`. The start of the window of time to examine, as an [ISO-8601][8601] compatible date/time string.
* `distinct_end_time`: used with `distinct_resources`. The end of the window of time to examine, as an [ISO-8601][8601] compatible date/time string.

### Query operators

See [the AST query language page][ast] for the full list of available operators.

### Query fields

> **Note: Fields that allow `NULL` values**
>
> In the case of a `skipped` resource event, some of the fields of an event may
> not have values. Queries using equality (`=`) and inequality (`!=`) will not return
> null values. See the `null?` operator, if you want to query for nodes that do not
> have a value.

* `certname` (string): the name of the node on which the event occurred.

* `report` (string): the ID of the report that the event occurred in. These IDs can be acquired via event queries or via the [`/reports`][report] query endpoint.

* `status` (string): the status of the event. Legal values are `success`, `failure`, `noop`, and `skipped`.

* `timestamp` (timestamp): the timestamp (from the Puppet agent) at which the event occurred. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `run_start_time` (timestamp): the timestamp (from the Puppet agent) at which the Puppet run began. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `run_end_time` (timestamp): the timestamp (from the Puppet agent) at which the Puppet run finished. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `report_receive_time` (timestamp): the timestamp (from the PuppetDB server) at which the Puppet report was received. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `resource_type` (string, with first letter always capitalized): the type of resource that the event occurred on, such as `File`, `Package`, etc.

* `resource_title` (string): the title of the resource on which the event occurred.

* `property` (string or null): the property/parameter of the resource on which the event occurred. For example, on a
  `Package` resource, this field might have a value of `ensure`. **Note:** this field
  may contain `NULL` values; see notes above.

* `new_value` (string or null): the new value that Puppet was attempting to set for the specified resource property.  Any [rich data][rich_data] values will appear as readable strings.  **Note:** this field may contain `NULL` values; see notes above.

* `old_value` (string or null): the previous value of the resource property, which Puppet was attempting to change.  Any [rich data][rich_data] values will appear as readable strings.  **Note:** this field may contain `NULL` values; see notes above.

* `message` (string or null): a description (supplied by the resource provider) of what happened during the event. **Note:** this field may contain `NULL` values; see notes above.

* `file` (string or null): the manifest file in which the resource definition is located.
  **Note:** this field may contain `NULL` values; see notes above.

* `line` (number or null): the line (of the containing manifest file) at which the resource definition can be found. **Note:** this field may contain `NULL` values; see notes above.

* `containing_class` (string or null): the Puppet class where this resource is declared.  **Note:** this field may contain `NULL` values; see notes above.

* `latest_report?` (Boolean): whether the event occurred in the most recent Puppet run (per-node). **Note:** the value of this field is always Boolean (`true` or `false` without quotes), and it is not supported by the regex match operator.

* `environment` (string): the environment associated with the reporting node.

* `configuration_version` (string): an identifier string that Puppet uses to match a specific catalog for a node to a specific Puppet run.

* `containment_path` (array of strings, where each string is a containment path element): the containment path associated with the event, as an ordered array that ends with the most specific containing element.

* `corrective_change` (boolean): whether or not the event represents a
  "corrective change", meaning the event rectified configuration drift.

### Subquery relationships

The following list contains related entities that can be used to constrain the result set using implicit subqueries. For more information consult the documentation for [subqueries][subqueries].

* [`reports`][reports]: the report associated with an event.
* [`environments`][environments]: the environment associated with an event.

### Response format

The response is a JSON array of events that match the input parameters. The array is unordered.

    [
      {
        "certname": "foo.localdomain",
        "old_value": "absent",
        "property": "ensure",
        "timestamp": "2012-10-30T19:01:05.000Z",
        "resource_type": "File",
        "resource_title": "/tmp/reportingfoo",
        "new_value": "file",
        "message": "defined content as '{md5}49f68a5c8493ec2c0bf489821c21fc3b'",
        "report": "38ff2aef3ffb7800fe85b322280ade2b867c8d27",
        "status": "success",
        "file": "/home/user/path/to/manifest.pp",
        "line": 6,
        "containment_path": [ "Stage[main]", "Foo", "File[/tmp/reportingfoo]" ],
        "containing_class": "Foo",
        "corrective_change": true,
        "run_start_time": "2012-10-30T19:00:00.000Z",
        "run_end_time": "2012-10-30T19:05:00.000Z",
        "report_receive_time": "2012-10-30T19:06:00.000Z"
      },
      {
        "certname": "foo.localdomain",
        "old_value": "absent",
        "property": "message",
        "timestamp": "2012-10-30T19:01:05.000Z",
        "resource_type": "Notify",
        "resource_title": "notify, yo",
        "new_value": "notify, yo",
        "message": "defined 'message' as 'notify, yo'",
        "report": "38ff2aef3ffb7800fe85b322280ade2b867c8d27",
        "status": "success",
        "file": "/home/user/path/to/manifest.pp",
        "line": 10,
        "containment_path": [ "Stage[main]", "", "Node[default]", "Notify[notify, yo]" ],
        "containing_class": null,
        "corrective_change": true,
        "run_start_time": "2012-10-30T19:00:00.000Z",
        "run_end_time": "2012-10-30T19:05:00.000Z",
        "report_receive_time": "2012-10-30T19:06:00.000Z"
      }
    ]

### Examples

[Using `curl`][curl] to query information about events:

    curl -G 'http://localhost:8080/pdb/query/v4/events' \
      --data-urlencode 'query=["=", "report", "38ff2aef3ffb7800fe85b322280ade2b867c8d27"]' \
      --data-urlencode 'limit=1000'

For all events in the report with hash
'38ff2aef3ffb7800fe85b322280ade2b867c8d27', use this JSON query structure:

    ["=", "report", "38ff2aef3ffb7800fe85b322280ade2b867c8d27"]

To retrieve all of the events within a given time period:

    ["and", ["<", "timestamp", "2011-01-01T12:01:00-03:00"],
      [">", "timestamp", "2011-01-01T12:00:00-03:00"]]

To retrieve all of the 'failure' events for nodes with name matching 'foo.\*'
and resources of type 'Service':

    ["and", ["=", "status", "failure"],
      ["~", "certname", "foo.\*"],
      ["=", "resource_type", "Service"]]

To retrieve latest events that are tied to the class found in your update.pp file:

    ["and", ["=", "latest_report?", true],
      ["~", "file", "update.pp"]]

To retrieve event status counts for each node:

    curl -X GET http://localhost:8080/pdb/query/v4/events --data-urlencode \
    'query=["extract", [["function", "count"], "status","certname"],
                       ["group_by","status","certname"]]'

## Paging

This endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].

