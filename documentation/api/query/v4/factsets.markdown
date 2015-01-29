---
title: "PuppetDB 2.2 » API » v4 » Querying Factsets"
layout: default
canonical: "/puppetdb/latest/api/query/v4/factsets.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

You can query factsets by making an HTTP request to the `/factsets` endpoint.

A factset is the set of all facts for a single certname.

### `GET /v4/factsets`

This will return all factsets matching the given query.

### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [the page on query structure.][query]

    If a query parameter is not provided, all results will be returned.

### Query Operators

See [the Operators page.](./operators.html)

### Query Fields

* `certname` (string): the certname associated with the factset
* `environment` (string): the environment associated with the fact
* `timestamp` (string): the most recent time of fact submission from the
   associated certname
* `producer_timestamp` (string): the most recent time of fact submission for
  the relevant certname from the master. Generation of this field will
  be pushed back to the agent in a later release, so it should not be relied on
  in its current form (use the `timestamp` field instead.)
* `hash` (string): a hash of the factset's certname, environment,
  timestamp, facts, and producer_timestamp.

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON string.

The result will be a JSON array with one entry per certname. Each entry is of
the form:

    {
      "certname": <node name>,
      "environment": <node environment>,
      "timestamp": <time of last fact submission>,
      "producer_timestamp": <time of command submission from master>,
      "facts": <facts for node>,
      "hash": <sha1 sum of "facts" value>
    }

The value of "facts" is a map describing facts for the node. The array is
unsorted.

### Examples

[Using `curl` from localhost][curl]:

Get the factset for node "example.com":

    curl -X GET http://puppetdb:8080/v4/factsets --data-urlencode 'query=["=", "certname", "example.com"]'

Get all factsets with updated after "2014-07-21T16:13:44.334Z":

    curl -X GET http://puppetdb:8080/v4/factsets --data-urlencode 'query=[">",
    "timestamp", "2014-07-21T16:13:44.334Z"]

Get all factsets corresponding to nodes running Darwin:

    curl -X GET http://puppetdb:8080/v4/factsets --data-urlencode 'query=["in",
    "certname", ["extract", "certname", ["select_facts", ["and", ["=", "name",
    "operatingsystem"], ["=", "value", "Darwin"]]]]]'

which returns

    [ {
      "facts" : {
        "operatingsystem" : "Darwin",

        <additional facts>

      },
      "timestamp" : "2014-07-25T16:39:06.265Z",
      "producer_timestamp" : "2014-07-25T16:39:06.265Z",
      "environment" : "production",
      "certname" : "desktop.localdomain",
      "hash": "b920822bc3872c9e2977cf40f87811393ead71aa"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
