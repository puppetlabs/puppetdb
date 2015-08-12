---
title: "PuppetDB 3.0 » API » v4 » Querying Fact Names"
layout: default
canonical: "/puppetdb/latest/api/query/v4/fact-names.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html

The `/fact-names` endpoint can be used to retrieve all known fact names.


## `GET /pdb/query/v4/fact-names`

This will return an alphabetical list of all known fact names, *including* those which are
known only for deactivated nodes.


### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation
(`["<OPERATOR>", "<FIELD>", "<VALUE>"]`). See the sections below for the
supported operators and fields. For general info about queries,
see [the page on query structure.][query]

If a query parameter is not provided, all results will be returned.

### Response Format

The response will be in `application/json`, and will contain an alphabetical
JSON array containing fact names. Each fact name will appear only once,
regardless of how many nodes have that fact.

    [<fact>, <fact>, ..., <fact>, <fact>]

### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/query/v4/fact-names

    ["kernel", "operatingsystem", "osfamily", "uptime"]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].

