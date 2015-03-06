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

Get all factsets corresponding to nodes with uptime greater than 24 hours:

    curl -X GET http://localhost:8080/v4/factsets -d 'query=["in", "certname",
    ["extract", "certname", ["select_facts", ["and", ["=", "name", "uptime_hours"], [">", "value", 24]]]]]'

which returns

    [ {
      "facts" : {
        "blockdevice_sde_vendor" : "Generic",
        "mtu_wlp5s0" : 1500,
        "processor6" : "Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz",
        "kernel" : "Linux",
        "physicalprocessorcount" : 1,
        "interfaces" : "br0,docker0,enp3s0,lo,tap0,wlp5s0",
        "lsbdistcodename" : "n/a",
        "rubyversion" : "1.9.3",
        "blockdevice_sdb_vendor" : "ATA",
        "trusted" : {
          "authenticated" : "remote",
          "certname" : "desktop.localdomain",
          "extensions" : { }
        },
        "swapsize" : "0.00 MB",
        "system_uptime" : {
          "days" : 5,
          "hours" : 120,
          "seconds" : 433391,
          "uptime" : "5 days"
        },
        "swapfree_mb" : "0.00",
        "netmask_br0" : "255.255.255.0",
        "gid" : "wyatt",
        "processorcount" : 8,
        "network_docker0" : "172.17.0.0",
        "mtu_docker0" : 1500,
        "swapfree" : "0.00 MB",
        ...
      },
      "producer_timestamp" : "2015-03-06T00:20:14.833Z",
      "timestamp" : "2015-03-06T00:20:14.918Z",
      "environment" : "production",
      "certname" : "desktop.localdomain",
      "hash" : "d118d161990f202e911b6fda09f79d24f3a5d4f4"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
