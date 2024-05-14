---
title: "Factsets endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/factsets.html"
---

# Factsets endpoint

[curl]: ../curl.markdown#using-curl-from-localhost-non-sslhttp
[facts]: ./facts.markdown
[paging]: ./paging.markdown
[query]: query.markdown
[subqueries]: ./ast.markdown#subquery-operators
[ast]: ./ast.markdown
[facts]: ./facts.markdown
[fact-contents]: ./fact-contents.markdown
[environments]: ./environments.markdown
[producers]: ./producers.markdown
[nodes]: ./nodes.markdown

The `/factsets` endpoint provides access to a represntation of node
factsets where each result includes the structured facts for a node
broken down into a vector of top-level key/value pairs.  Note that the
`inventory` endpoint will often provide more flexible and efficient
access to the same information.

## `/pdb/query/v4/factsets`

This will return all factsets matching the given query.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation (`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the supported operators and fields. For general info about queries, see [our guide to query structure.][query]

If a query parameter is not provided, all results will be returned.

### Query operators

See [the AST query language page][ast].

### Query fields

* `certname` (string): the certname associated with the factset.
* `environment` (string): the environment associated with the fact.
* `timestamp` (string): the most recent time of fact submission from the
   associated certname.
* `producer_timestamp` (string): the most recent time of fact submission for
  the relevant certname from the Puppet Server.
* `producer` (string): the certname of the Puppet Server that sent the factset to PuppetDB.
* `hash` (string): a hash of the factset's certname, environment,
  timestamp, facts, and producer_timestamp.

### Subquery relationships

The following list contains related entities that can be used to constrain the result set using implicit subqueries. For more information, consult the documentation for [subqueries][subqueries].

* [`environments`][environments]: the environment a factset was received from.
* [`producers`][producers]: the Puppet Server that sent the factset to PuppetDB.

### Response format

Successful responses will be in `application/json`. Errors will be returned as a
non-JSON string.

The result will be a JSON array with one entry per certname. Each entry will be in
the form:

    {
      "certname": <node name>,
      "environment": <node environment>,
      "timestamp": <time of last fact submission>,
      "producer_timestamp": <time of command submission from Puppet Server>,
      "producer": <Puppet Server certname>
      "facts": <expanded facts>,
      "hash": <sha1 sum of "facts" value>
    }

The `<expanded facts>` object is an expansion of the following form:

    {
      "href": <url>,
      "data": [ {
        "name": <string>,
        "value": <any>
      } ... ]
    }

### Examples

[Using `curl` from localhost][curl]:

Get the factset for node "example.com":

    curl -X GET http://localhost:8080/pdb/query/v4/factsets \
      --data-urlencode 'query=["=", "certname", "example.com"]'

Get all factsets with updated after "2014-07-21T16:13:44.334Z":

    curl -X GET http://localhost:8080/pdb/query/v4/factsets \
      --data-urlencode 'query=[">", "timestamp", "2014-07-21T16:13:44.334Z"]

Get all factsets corresponding to nodes with uptime greater than 24 hours:

    curl -X GET http://localhost:8080/pdb/query/v4/factsets \
      -d 'query=["in", "certname",
                  ["extract", "certname",
                    ["select_facts", ["and", ["=", "name", "uptime_hours"],
                                             [">", "value", 24]]]]]'

Which returns:

    [ {
      "facts" : {
        "href": "/pdb/query/v4/factsets/desktop.localdomain/facts",
        "data": [
          {
            "name": "blockdevice_sde_vendor",
            "value": "Generic"
          },
          {
            "name": "mtu_wlp5s0",
            "value": 1500
          },
          {
            "name": "processor6",
            "value": "Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz"
          },
          {
            "name": "trusted",
            "value" : {
              "authenticated" : "remote",
              "certname" : "desktop.localdomain",
              "extensions" : { }
            },
          },
          {
            "name": "system_uptime",
            "value": {
              "days" : 5,
              "hours" : 120,
              "seconds" : 433391,
              "uptime" : "5 days"
            },
          },
          ...
        ]
      },
      "producer" : "server.localdomain",
      "producer_timestamp" : "2015-03-06T00:20:14.833Z",
      "timestamp" : "2015-03-06T00:20:14.918Z",
      "environment" : "production",
      "certname" : "desktop.localdomain",
      "hash" : "d118d161990f202e911b6fda09f79d24f3a5d4f4"
    } ]

## `/pdb/query/v4/factsets/<NODE>`

This will return the most recent factset for the given node. Supplying a node
this way will restrict any given query to only apply to that node, but in
practice this endpoint is typically used without a query string or URL
parameters.

The result will be a single map of the factset structure described above, or
a JSON error message if the factset is not found.

### Examples

    curl 'http://localhost:8080/pdb/query/v4/factsets/kb.local'

    {
      "certname" : "kb.local",
      "environment" : "production",
      "facts" : {...},
      "hash" : "93253d31af6d718cf81f5bc028be2a671f23ed78",
      "producer" : "foo.local",
      "producer_timestamp" : "2015-06-04T15:27:56.893Z",
      "timestamp" : "2015-06-04T15:27:56.979Z"
    }

    curl -X GET http://localhost:8080/pdb/query/v4/factsets/my_fake_hostname

    {
      "error" : "No information is known about factset my_fake_hostname"
    }


## `/pdb/query/v4/factsets/<NODE>/facts`

This will return all facts for a particular factset, designated by a node certname.

This is a shortcut to the [`/facts`][facts] endpoint. It behaves the same as a
call to [`/facts`][facts] with a query string of `["=", "certname", "<NODE>"]`,
except results are returned even if the node is deactivated or expired.

### URL parameters / query operators / query fields / response format

This route is an extension of the [`facts`][facts] endpoint. It uses the same parameters, operators, fields, and response format.

If you provide a `query` parameter, it will specify additional criteria, which will be
used to return a subset of the information normally returned by this route.

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, see the documentation
on [paging][paging].
