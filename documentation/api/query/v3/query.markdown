---
title: "PuppetDB 2.1 » API » v3 » Query Structure"
layout: default
canonical: "/puppetdb/latest/api/query/v3/query.html"
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

PuppetDB's query API can retrieve data objects from PuppetDB for use in other applications. For example, the terminus plugins for puppet masters use this API to collect exported resources, and to translate node facts into the inventory service.

The query API is implemented as HTTP URLs on the PuppetDB server. By default, it can only be accessed over the network via host-verified HTTPS; [see the jetty settings][jetty] if you need to access the API over unencrypted HTTP.

## API URLs

API URLs generally look like `https://<SERVER>:<PORT>/<API VERSION>/<ENDPOINT>`. (For example, `https://puppetdb:8081/v3/resources`.) A full API call will often also include some URL parameters, in the standard `https://url?parameter=value&parameter=value` format.

The first component of an API URL is the API version, written as `v2`, `v3`, etc. This page describes version 3 of the API, so every URL will begin with `/v3`. After the version, URLs are organized into a number of **endpoints.**

### Endpoints

Conceptually, an endpoint represents a reservoir of some type of PuppetDB object. Each version of the PuppetDB API defines a set number of endpoints.

See the [API index][index] for a list of the available endpoints. Each endpoint may have additional sub-endpoints under it; these are generally just shortcuts for the most common types of query, so that you can write terser and simpler query strings.

## Query Structure

A query consists of:

* An HTTP GET request to an endpoint URL...
* ...which may or may not contain a `query` URL parameter, whose value is a **query string...**
* ...and which may or may not contain other URL parameters, to configure [paging][] or other behavior.

That is, nearly every query will look like a GET request to a URL that resembles the following:

    https://puppetdb:8081/v3/<ENDPOINT>?query=<QUERY STRING>

Query strings are optional for some endpoints, required for others, and prohibited for others; see each endpoint's documentation.

### Paging

Most PuppetDB query endpoints support paged results via the common PuppetDB paging
URL parameters.  For more information, please see the documentation
on [paging][paging].

### Query Strings

A query string must be:

* A [URL-encoded][urlencode]...
* ...JSON array...
    * ...which may contain scalar data types (usually strings) and additional arrays...
* ...which describes a complex _comparison operation..._
* ...in [_prefix notation_][prefix], with an **operator** first and its **arguments** following.

That is, before being URL-encoded, all query strings follow the form:

    [ "<OPERATOR>", "<ARGUMENT>", (..."<ARGUMENT>"...) ]

A complete query string describes a comparison operation. When submitting a query, PuppetDB will check every _possible_ result from the endpoint to see if it matches the comparison from the query string, and will only return those objects that match.

Different operators may take different numbers (and types) of arguments. Each endpoint may have a slightly different set of operators available.

> #### Explicit Grammar for Query Strings
>
> More explicitly, the following grammar describes a query string (before it is URL-encoded):
>
>     query: [ {bool}, {query}+ ] | [ "not", {query} ] | [ {binary_op}, {field}, {value} ] |
>                 [ "in", {field}, [ "extract", {field}, [ {subquery_name}, {query} ] ] ]
>     field:          string, which is the name of a valid FIELD listed in the endpoint's doc page
>     value:          string
>     bool:           "or" | "and"
>     binary_op:      "=" | "~" | ">" | ">=" | "<" | "<="
>     subquery_name:  "select-resources" | "select-facts" | "select-nodes"

> #### Note on JSON Formatting
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
