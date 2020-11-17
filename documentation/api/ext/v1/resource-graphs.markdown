---
title: "Extensions API (PE only)"
layout: default
canonical: "/puppetdb/latest/api/ext/v1/resource-graphs.html"
---

[paging]: ../../query/v4/paging.html
[query]: ../../query/v4/query.html
[subqueries]: ../../query/v4/ast.html#subquery-operators
[ast]: ../../query/v4/ast.html
[environments]: ../../query/v4/environments.html
[nodes]: ../../query/v4/nodes.html
[statuses]: {{puppet}}/format_report.html#puppettransactionreport

You can query resource-graphs by making an HTTP request to the
`/pdb/ext/v1/resource-graphs` endpoint.

## `/pdb/ext/v1/resource-graphs`

This will return a JSON array containing all the resource-graphs for each node
in your infrastructure.

This resource-graph is a unified view of data from the report and the catalog so
that all resource information from a puppet run (parameters from catalogs,
events from reports, etc.) is present in one place.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation
  (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the
  supported operators and fields. For general info about queries, see
  [the page on query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query Operators

See [the AST query language page][ast].

### Query Fields

* `certname` (string): the certname associated with the resource-graph
* `environment` (string): the environment assigned to the node that submitted
  the report.
* `transaction_uuid` (string): string used to identify a puppet run.
* `catalog_uuid` (string): a string used to tie a catalog to its associated
  reports
* `code_id` (string): a string used to tie a catalog to the Puppet code which
  generated the catalog
* `producer_timestamp` (timestamp): is the time of catalog submission from the
  Puppet Server to PuppetDB, according to the clock on the Puppet Server. Timestamps are
  always [ISO-8601][8601] compatible date/time strings.
* `status` (string): the status associated to report's node. Possible values for
  this field come from Puppet's report status, which can be found
  [here][statuses].
* `noop` (boolean): a flag indicating whether the report was produced by a noop
  run.

### Subquery Relationships

Here is a list of related entities that can be used to constrain the result set
using implicit subqueries. For more information consult the documentation for
[subqueries][subqueries].

* [`nodes`][nodes]: Node for a catalog.
* [`environments`][environments]: Environment for a catalog.

### Response Format

Successful responses will be in `application/json`.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "certname" : <node certname>,
      "environment" : <resource-graph environment>,
      "catalog_uuid" : <string to identify catalog>,
      "transaction_uuid" : <string to identify puppet run>,
      "code_id" : <string to identify puppet code>,
      "producer_timestamp": <time of transmission by Puppet Server>,
      "status": <status of node after report's associated puppet run>,
      "noop": <boolean flag indicating noop run>,
      "resources" : <resources>,
      "edges" : <edges>
    }

The `<resources>` object is of the following form:

    {
      "resource": <string>,
      "type": <string>,
      "title": <sttring>,
      "exported": <boolean>,
      "tags": [<tags>, ...],
      "file": <string>,
      "line": <number>,
      "parameters": <any>,
      "skipped" : <boolean for whether or not the resource was skipped>, 
      "events" : [<event> ...]
    }

where an `<event>` object is of the form:

    {
      "timestamp": <timestamp (from agent) at which event occurred>,
      "property": <property/parameter of resource on which event occurred>,
      "new_value": <new value for resource property>,
      "old_value": <old value of resource property>,
      "status": <status of event (`success`, `failure`, or `noop`)>,
      "message": <description of what happened during event>
    }

The `<edges>` object is of the follow form:

    {
      "relationship": <string>,
      "source_title": <string>,
      "source_type": <string>,
      "target_title": <string>,
      "target_type": <string>
    }

### Examples

This query will return the latest two resource-graphs for a node `host-1`:

    curl -X GET http://localhost:8080/pdb/ext/v1/resource-graphs \
    --data-urlencode 'query=["=","certname","host-1"]' \
    --data-urlencode 'limit=1' 

    [
      {
        "catalog_uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
        "certname": "host-1",
        "code_id": null,
        "environment": "production",
        "noop": false,
        "producer_timestamp": "2016-01-07T20:40:21.119Z",
        "status": "changed",
        "transaction_uuid": "2211965a-826d-4bb7-a8ab-9eef8c0c43d1",
        "edges" : [...],
        "resources" : [...]
      }
    ]

## Paging

The v1 resource-graphs endpoint supports all the usual paging URL parameters
described in the documents on [paging][paging]. Ordering is allowed on every
queryable field.
