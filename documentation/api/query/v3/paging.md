---
title: "PuppetDB 1.4 » API » v3 » Paged Queries"
layout: default
canonical: "/puppetdb/latest/api/query/v3/paging.html"
---

[api]: ../../index.html
[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Most of PuppetDB's [query endpoints][api] support a general set of HTTP query parameters that
can be used for paging results.

> **Note:** The operators below apply to **version 3** of the query API.  They are not available in v1 or v2 queries.

## Query Parameters

### `order-by`

This parameter can be used to ask PuppetDB to return results sorted by one or more fields, in
ascending or descending order.  The value must be an array of maps.  Each map represents a field
to sort by, and the order that they are specified in the array determines the precedence for the
sorting.

Each map must contain the key `field`, whose value must be the name of a field that can be
returned by the specified query.

Each map may also optionally contain the key `order`, whose value may either be `"asc"` or
`"desc"`, depending on whether you wish the field to be sorted in ascending or descending
order.  The default if this key is not specified is `"asc"`.

Note that the legal values for `field` vary depending on which endpoint you are querying; for
lists of legal fields, please refer to the documentation for the specific query endpoints.

#### Example:

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/v3/facts --datrlencode 'order-by=[{"field": "value", "order": "desc"}, {"field": "name"}]'

### `limit`

This query parameter can be used to restrict the result set to a maximum number of results.
The value should be an integer.

#### Example:

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/v3/facts --data-urlencode 'limit=5'

### `offset`

This parameter can be used to tell PuppetDB to return results beginning at the specified offset.
For example, if you'd like to page through query results with a page size of 10, your first
query could specify `limit=10` and `offset=0`, your second query would specify `limit=10` and
`offset=10`, etc.

This value should be an integer.  Note that the order in which results are returned by PuppetDB
is not guaranteed to be consistent unless you specify a value for `order-by`, so this parameter
should generally be used in conjunction with `order-by`.

#### Example:

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/v3/facts --data-urlencode 'order-by=[{"field": "value"}]' --data-urlencode 'limit=5' --data-urlencode 'offset=5'

