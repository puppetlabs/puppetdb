---
title: "Nodes endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/nodes.html"
---

# Nodes endpoint

[resource]: ./resources.html
[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[statuses]: {{puppet}}/format_report.html#puppettransactionreport
[paging]: ./paging.html
[query]: ./query.html
[8601]: http://en.wikipedia.org/wiki/ISO_8601
[subqueries]: ./ast.html#subquery-operators
[ast]: ./ast.html
[factsets]: ./factsets.html
[reports]: ./reports.html
[catalogs]: ./catalogs.html
[facts]: ./facts.html
[fact-contents]: ./fact-contents.html
[events]: ./events.html
[edges]: ./edges.html
[resources]: ./resources.html
[inventory]: ./inventory.html
[expirev1]: ../../wire_format/configure_expiration_format_v1.html

Nodes can be queried by making an HTTP request to the `/nodes` endpoint.

## `/pdb/query/v4/nodes`

This will return all nodes matching the given query. Deactivated and expired
nodes aren't included in the response.

### URL parameters

* `query`: optional. A JSON array of query predicates, in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [our guide to query structure.][query]

To query for the latest fact/catalog/report timestamp submitted by 'example.local', the JSON query structure would be:

    ["=", "certname", "example.local"]

### Query operators

See [the AST query language page][ast].

### Query fields

The below fields are allowed as filter criteria and are returned in all responses.

* `certname` (string): the name of the node that the report was received from.

* `catalog_environment` (string): the environment for the last received catalog.

* `facts_environment` (string): the environment for the last received fact set.

* `report_environment` (string): the environment for the last received report.

* `catalog_timestamp` (timestamp): the last time a catalog was received. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `facts_timestamp` (timestamp): the last time a fact set was received. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `report_timestamp` (timestamp): the last time a report run was complete. Timestamps are always [ISO-8601][8601] compatible date/time strings.

* `latest_report_status` (string): the status of the latest report. Possible values
  come from Puppet's report status, which can be found [here][statuses].

* `latest_report_noop` (boolean): indicates whether the most recent report for
  the node was a noop run.

* `latest_report_noop_pending` (boolean): indicates whether the most recent
  report for the node contained noop events.

* `latest_report_corrective_change` (boolean): a flag indicating whether the latest
  report for the node included events that remediated configuration drift. This
  field is only populated in PE.

* `cached_catalog_status` (string): Cached catalog status of the
  last puppet run for the node. Possible values are `explicitly_requested`,
  `on_failure`, `not_used` or `null`.

* `latest_report_hash` (string): a hash of the latest report for the node.

* `latest_report_job_id` (string): the job id associated with the latest report (not present if the run wasn't part of a job).

* `["fact", <FACT NAME>]` (string, number, Boolean): the value of `<FACT NAME>` for a node. Inequality operators are allowed, and will skip non-numeric values.

    Note that nodes which are missing a fact referenced by a `not` query will match
    the query.

* `expires_facts` (boolean): indicates whether or not factsets for the
  node will be a candidate for expiration.  This field will only be
  visible if the `include_facts_expiration` query parameter is set to
  `true`.

> *Note*: configuration of fact expiration is an experimental feature
> which might be altered or removed in a future release, and for the
> time being, PuppetDB exports will not include this information.

* `expires_facts_updated` (timestamp or null): indicates when the
  value of `expires_facts` was last changed.  This will be `null` if
  the value has never been explicitly set by a [configure expiration][expirev1]
  command.  This field will only be visible if the
  `include_facts_expiration` query parameter is set to true.

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {
     "certname": <string>,
     "deactivated": <timestamp or null>,
     "expired": <timestamp or null>,
     "catalog_timestamp": <timestamp or null>,
     "facts_timestamp": <timestamp or null>,
     "report_timestamp": <timestamp or null>,
     "catalog_environment": <string or null>,
     "facts_environment": <string or null>,
     "report_environment": <string or null>,
     "latest_report_status": <string>,
     "latest_report_noop": <boolean>,
     "latest_report_noop_pending": <boolean>,
     "latest_report_hash": <string>,
     "latest_report_job_id": <string or null>
    }

At least one of the `_timestamp` fields will be non-null.

The array is unsorted.

If no nodes match the query, an empty JSON array will be returned.

### Examples

[You can use `curl`][curl] to query information about nodes:

    curl http://localhost:8080/pdb/query/v4/nodes

This query will return nodes whose kernel is Linux and whose uptime is less
than 30 days:

    ["and",
      ["=", ["fact", "kernel"], "Linux"],
      [">", ["fact", "uptime_days"], 30]]

This query will return node counts for each distinct `facts_environment` among
active nodes:

    ["extract", [["function","count"],"facts_environment"],
      ["null?", "deactivated", true],
      ["group_by", "facts_environment"]]

This query, which includes a subquery against the fact-contents endpoint, will
return all nodes with at least 10 days of uptime:

    ["in", "certname",
      ["extract", "certname",
        ["select_fact_contents",
          ["and", ["=", "path", ["system_uptime", "days"]],
                  [">=", "value", 10]]]]]

## `/pdb/query/v4/nodes/<NODE>`

This will return status information for the given node, active or
not. It behaves exactly like a call to `/pdb/query/v4/nodes` with a query string
of `["=", "certname", "<NODE>"]`.

    curl -X GET http://localhost:8080/pdb/query/v4/nodes/mbp.local
    {
        "deactivated" : null,
        "facts_environment" : "production",
        "report_environment" : "production",
        "catalog_environment" : "production",
        "facts_timestamp" : "2015-06-19T23:03:42.401Z",
        "expired" : null,
        "report_timestamp" : "2015-06-19T23:03:37.709Z",
        "certname" : "mbp.local",
        "catalog_timestamp" : "2015-06-19T23:03:43.007Z",
        "latest_report_status": "success",
        "latest_report_noop": false,
        "latest_report_noop_pending": true,
        "latest_report_hash": "2625d1b601e98ed1e281ccd79ca8d16b9f74fea6",
        "latest_report_job_id": null
    }

### URL parameters / query operators / query fields

This route is an extension of the plain `nodes` endpoint. It uses the same parameters, operators, and fields.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by this route.

### Response format

The response is a single hash in the same form used for the plain `nodes` endpoint shown above.

If a node of that certname doesn't exist, the response will instead be a hash of the form:

    {"error": "No information is known about <NODE>"}

## `/pdb/query/v4/nodes/<NODE>/facts`

[facts]: ./facts.html

This will return the facts for the given node. Facts from deactivated and
expired nodes aren't included in the response.

This is a shortcut to the [`/pdb/query/v4/facts`][facts] endpoint. It
behaves the same as a call to [`/pdb/query/v4/facts`][facts] with a
query string of `["=", "certname", "<NODE>"]`.

### URL parameters / query operators / query fields / response format

This route is an extension of the `facts` endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `/pdb/query/v4/nodes/<NODE>/facts/<NAME>`

This will return facts with the given name for the given node. Facts from
deactivated and expired nodes aren't included in the response.

This is a shortcut to the [`/pdb/query/v4/facts`][facts] endpoint. It
behaves the same as a call to [`/pdb/query/v4/facts`][facts] with a
query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "name", "<NAME>"]]

### URL parameters / query operators / query fields / response format

This route is an extension of the [`facts`][facts] endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `/pdb/query/v4/nodes/<NODE>/facts/<NAME>/<VALUE>`

This will return facts with the given name and value for the given node. Facts
from deactivated and expired nodes aren't included in the response.

This is a shortcut to the [`/pdb/query/v4/facts`][facts] endpoint. It
behaves the same as a call to [`/pdb/query/v4/facts`][facts] with a
query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "name", "<NAME>"],
        ["=", "value", "<VALUE>"]]

### URL parameters / query operators / query fields / response format

This route is an extension of the [`facts`][facts] endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

(However, for this particular route, there aren't any practical criteria left.)


## `/pdb/query/v4/nodes/<NODE>/resources`

This will return the resources for the given node. Resources from deactivated
and expired nodes aren't included in the response.

This is a shortcut to the [`/pdb/query/v4/resources`][resource]
route. It behaves the same as a call to
[`/pdb/query/v4/resources`][resource] with a query string of
`["=", "certname", "<NODE>"]`.

### URL parameters / query operators / query fields / response format

This route is an extension of the [`resources`][resource] endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `/pdb/query/v4/nodes/<NODE>/resources/<TYPE>`

This will return the resources of the indicated type for the given
node. Resources from deactivated and expired nodes aren't included in the
response.

This is a shortcut to the [`/pdb/query/v4/resources/<TYPE>`][resource]
route. It behaves the same as a call to
[`/pdb/query/v4/resources`][resource] with a query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "type", "<TYPE>"]]

### URL parameters / query operators / query fields / response format

This route is an extension of the [`resources`][resource] endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.

## `/pdb/query/v4/nodes/<NODE>/resources/<TYPE>/<TITLE>`

This will return the resource of the indicated type and title for the given
node. Resources from deactivated and expired nodes aren't included in the
response.

This is a shortcut to the
[`/pdb/query/v4/resources/<TYPE>/<TITLE>`][resource] route. It behaves
the same as a call to [`/pdb/query/v4/resources`][resource] with a
query string of:

    ["and",
        ["=", "certname", "<NODE>"],
        ["=", "type", "<TYPE>"],
        ["=", "title", "<TITLE>"]]

### URL parameters / query operators / query fields / response format

This route is an extension of the [`resources`][resource] endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by
this route.


## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
