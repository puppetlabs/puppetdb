---
title: "Query paging"
layout: default
canonical: "/puppetdb/latest/api/query/v4/paging.html"
---
# Query paging

[api]: ../../overview.markdown
[curl]: ../curl.markdown#using-curl-from-localhost-non-sslhttp
[query]: query.markdown
[ast]: ./ast.markdown#paging-operators-limit-offset-orderby

Most of PuppetDB's [query endpoints][api] support a general set of HTTP URL parameters that
can be used for paging results. PuppetDB also supports paging via query
operators, as described in the [AST documentation][ast].

## URL parameters for paging results

### `order_by`

This parameter can be used to ask PuppetDB to return results sorted by one or more fields, in ascending or descending order. The value must be a JSON array of maps. Each map represents a field to sort by, and the order in which the maps are specified in the array determines the sort order.

Each map must contain the key `field`, whose value must be the name of a field that can be
returned by the specified query.

Each map may also optionally contain the key `order`, whose value may either be `"asc"` or
`"desc"`, depending on whether you wish the field to be sorted in ascending or descending
order. The default value for this key, if not specified, is `"asc"`.

Note that the legal values for `field` vary depending on which endpoint you are querying. For lists of legal fields, please refer to the documentation for the specific query endpoints.

#### Example:

[Using `curl` from localhost][curl]:

This query will order the results of the facts endpoint in descending order by
`certname`, breaking ties with an ascending ordering `name`.

    curl -X GET http://localhost:8080/pdb/query/v4/facts --data-urlencode \
      'order_by=[{"field": "certname", "order": "desc"}, {"field": "name"}]'

### `limit`

This parameter can be used to restrict the result set to a maximum number of results.
The value should be an integer.

### `include_total`

This parameter lets you request a count of how many records would have been returned, had the query not been limited using the `limit` parameter. This is useful if you want your application to show how far the user has navigated ("page 3 of 15").

The value should be a Boolean, and defaults to `false`. If `true`, the HTTP response will contain a header `X-Records`, whose value is an integer indicating the total number of results available.

**Note:** Setting this flag to `true` will introduce a minor performance hit on the query.

#### Example:

[Using `curl` from localhost][curl]:

    curl -vv -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'limit=1' --data-urlencode 'include_total=true'

    * Hostname was NOT found in DNS cache
    *   Trying ::1...
    * Connected to localhost (::1) port 8080 (#0)
    > GET /pdb/query/v4/facts HTTP/1.1
    > User-Agent: curl/7.37.1
    > Host: localhost:8080
    > Accept: */*
    > Content-Length: 26
    > Content-Type: application/x-www-form-urlencoded
    >
    * upload completely sent off: 26 out of 26 bytes
    < HTTP/1.1 200 OK
    < Date: Mon, 22 Jun 2015 19:17:35 GMT
    < Content-Type: application/json; charset=utf-8
    < X-Records: 1148
    < Content-Length: 105
    * Server Jetty(9.2.10.v20150310) is not blacklisted
    < Server: Jetty(9.2.10.v20150310)
    <
    [ {
      "certname" : "host-0",
      "environment" : "production",
      "name" : "kernel",
      "value" : "Linux"
    } ]

### `offset`

This parameter can be used to tell PuppetDB to return results beginning at the specified offset. For example, if you'd like to page through query results with a page size of 10, your first query would specify `limit=10` and `offset=0`, your second query would specify `limit=10` and `offset=10`, and so on.

This value should be an integer. Note that the order in which results are returned by PuppetDB is not guaranteed to be consistent unless you specify a value for `order_by`, so this parameter should generally be used in conjunction with `order_by`.

#### Example:

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'order_by=[{"field": "certname"}]' \
      --data-urlencode 'limit=5' \
      --data-urlencode 'offset=5'

### `explain`

This parameter can be used to tell PuppetDB to return the execution plan of a statement. The execution plan shows how the table(s) referenced by the statement will be scanned, the estimated statement execution cost and the actual run time statistics. 

This value should be an the string "analyze", any other value will not be validated.

#### Example:

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/query/v4/facts \
      --data-urlencode 'order_by=[{"field": "certname"}]' \
      --data-urlencode 'explain=analyze'