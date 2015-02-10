---
title: "PuppetDB 2.2 » API » v4 » Query Structure"
layout: default
canonical: "/puppetdb/latest/api/query/v4/query.html"
---

[prefix]: http://en.wikipedia.org/wiki/Polish_notation
[jetty]: ../../../configure.html#jetty-http-settings
[index]: ../../index.html
[urlencode]: http://en.wikipedia.org/wiki/Percent-encoding
[operators]: ./operators.html
[tutorial]: ../tutorial.html
[curl]: ../curl.html
[paging]: ./paging.html

## Summary

PuppetDB's query API can retrieve data objects from PuppetDB for use in other applications. For example, the PuppetDB termini for puppet masters use this API to collect exported resources.

The query API is implemented as HTTP URLs on the PuppetDB server. By default, it can only be accessed over the network via host-verified HTTPS; [see the jetty settings][jetty] if you need to access the API over unencrypted HTTP.

## Query Structure

A query consists of:

* An HTTP GET request to an endpoint URL...
* ...which may or may not contain a `query` URL parameter, whose value is a **query string...**
* ...and which may or may not contain other URL parameters, to configure [paging][] or other behavior.

That is, most queries will look like a GET request to a URL that resembles the following:

    https://puppetdb:8081/v4/<ENDPOINT>?query=<QUERY STRING>

### API URLs

API URLs generally look like this:

    https://<SERVER>:<PORT>/<API VERSION>/<ENDPOINT>?<PARAMETER>=<VALUE>&<PARAMETER>=<VALUE>

For example: `https://puppetdb:8081/v4/resources?limit=50&offset=50`.

### API Version

After the server and port, the first part of an API URL is the **API
version,** written as `v4`, etc. This section describes version 4 of
the API, so every URL will begin with `/v4`.

### Endpoints

After the version, URLs are organized into a number of **endpoints.**

Conceptually, an endpoint represents a reservoir of some type of PuppetDB object. Each version of the PuppetDB API defines a set number of endpoints.

See the [API index][index] for a list of the available endpoints. Each endpoint may have additional sub-endpoints under it; these are generally just shortcuts for the most common types of query, so that you can write terser and simpler query strings.

### URL Parameters

Finally, the URL may include some **URL parameters.** Some endpoints require certain parameters; for others they're optional or disallowed. Each endpoint's page lists the parameters it accepts, and most endpoints also support the [paging][] parameters.

A group of parameters begins with a question mark (`?`). Each parameter is formatted as `<PARAMETER>=<VALUE>`, and additional parameters are separated by ampersands (`&`). All parameter values must be [URL-encoded.][urlencode]

#### `query`

The most common URL parameter is `query`, which lets you define the set of results returned by most endpoints. The value of `query` must be a **query string.** Query strings are described in more detail [below](#query-strings).

#### Paging

The next most common URL parameters are the **paging** parameters.

Most PuppetDB query endpoints support paged results via a set of shared URL parameters.  For more information, please see the documentation on [paging][paging].

## Query Strings

A query string passed to the `query` URL parameter must be:

* A [URL-encoded][urlencode]...
* ...JSON array...
    * ...which may contain scalar data types (usually strings) and additional arrays...
* ...which describes a complex _comparison operation..._
* ...in [_prefix notation_][prefix], with an **operator** first and its **arguments** following.

That is, before being URL-encoded, all query strings follow the form:

    [ "<OPERATOR>", "<ARGUMENT>", (..."<ARGUMENT>"...) ]

A complete query string describes a comparison operation. When submitting a query, PuppetDB will check every _possible_ result from the endpoint to see if it matches the comparison from the query string, and will only return those objects that match.

Different operators may take different numbers (and types) of arguments. Each endpoint may have a slightly different set of operators available.

> ### Explicit Grammar for Query Strings
>
> More explicitly, the following grammar describes a query string (before it is URL-encoded):
>
>     query: [ {bool}, {query}+ ] | [ "not", {query} ] | [ {binary_op}, {field}, {value} ] |
>                 [ "in", {field}, [ "extract", {field}, [ {subquery_name}, {query} ] ] ]
>     field:          string, which is the name of a valid FIELD listed in the endpoint's doc page
>     value:          string
>     bool:           "or" | "and"
>     binary_op:      "=" | "~" | ">" | ">=" | "<" | "<=" | "null?"
>     subquery_name:  "select_resources" | "select_facts" | "select_nodes"

> ### Note on JSON Formatting
>
> JSON arrays are delimited by square brackets (`[` and `]`), and items in the array are separated by commas. JSON strings are delimited by straight double-quotes (`"`) and must be UTF-8 text; literal double quotes and literal backslashes in the string must be escaped with a backslash (`"` is `\"` and `\` is `\\`).

### Operators

[The Operators page][operators] describes all of the available operators and their behavior. Also, each endpoint's page will list which operators are useful with that endpoint.

PuppetDB uses three main kinds of operators:

* **Binary comparison operators** like `=` or `<`, which take exactly one **field** and exactly one **value** as arguments. (`["=", "certname", "magpie.example.com"]`)
* **Boolean operators** like `not` or `and`, which take complete query strings as arguments. `["and", ["<", "timestamp", "2011-01-01T12:01:00-03:00"], [">", "timestamp", "2011-01-01T12:00:00-03:00"]]`
* **Subquery operators,** which always occur in the form `["in", "<FIELD>", ["extract", "<FIELD>", <SUBQUERY STATEMENT>] ]`.


## Query Responses

All queries return data with a content type of `application/json`. Each endpoint's page describes the format of its return data.

## Tutorial and Tips

For a walkthrough on constructing queries, see [the Query Tutorial page][tutorial]. For quick tips on using curl to make ad-hoc queries, see [the Curl Tips page][curl].
