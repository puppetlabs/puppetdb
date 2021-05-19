---
title: "State overview endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/state-overview.html"
---

# State overview endpoint

[event-counts]: ./event-counts.html
[events]: ./events.html
[curl]: ../curl.html
[query]: ./query.html

> **PE-only**: The state-overview endpoint is only available for Puppet
> Enterprise.

The state-overview endpoint provides a convenient mechanism for getting counts
of nodes based on the status of their last report, or alternatively whether the
node is unresponsive or has not reported.

## `/pdb/ext/v1/state-overview`

### URL parameters

* `unresponsive_threshold`: required. The time (in seconds) since the last
  report after which a node is considered "unresponsive".

### Query operators

This endpoint accepts no queries.

### Response format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON hash of the following form:

    {
      "unresponsive": <number of nodes that have not reported since `unresponsive_threshold`>,
      "unreported": <number of nodes for which PuppetDB has not received a report>,
      "noop": <number of nodes for which the latest report was a noop>,
      "failed": <number of nodes for which the latest report had failures>,
      "unchanged": <number of nodes for which the latest report made no changes>,
      "changed": <number of nodes for which the latest report had successful changes>
    }

The statuses are assessed by evaluating the following precedence rules in order:
* If PuppetDB does not contain a report for a node, the status will be `unreported`
* If a report has not been received within the `unresponsive_threshold`, the
  node will be marked `unresponsive`.
* Otherwise, the status will be assessed based on the most recent report in
  PuppetDB.

### Examples

    curl -X GET http://localhost:8080/pdb/ext/v1/state-overview?unresponsive_threshold=3600

    {
      "unresponsive": 0,
      "unreported": 0,
      "noop": 10,
      "failed": 3,
      "unchanged": 50,
      "changed": 4
    }
