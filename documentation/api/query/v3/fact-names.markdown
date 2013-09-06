---
title: "PuppetDB 1.4 » API » v3 » Querying Fact Names"
layout: default
canonical: "/puppetdb/latest/api/query/v3/fact-names.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html

The `/fact-names` endpoint can be used to retrieve all known fact names.


## Routes

### `GET /fact-names`

This will return an alphabetical list of all known fact names, *including* those which are
known only for deactivated nodes.

#### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/v3/fact-names

    ["kernel", "operatingsystem", "osfamily", "uptime"]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
query parameters.  For more information, please see the documentation
on [paging][paging].

## Request

All requests `Accept` header must match `application/json`.

## Response Format

The response will be in `application/json`, and will contain an alphabetical
JSON array containing fact names. Each fact name will appear only once,
regardless of how many nodes have that fact.

    [<fact>, <fact>, ..., <fact>, <fact>]
