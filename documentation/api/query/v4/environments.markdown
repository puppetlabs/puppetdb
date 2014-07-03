---
title: "PuppetDB 2.1 » API » v4 » Querying Environments"
layout: default
canonical: "/puppetdb/latest/api/query/v4/environments.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[events]: ./events.html
[reports]: ./reports.html
[resources]: ./resources.html
[facts]: ./facts.html

Environments can be queried by making an HTTP request to the `/environments` REST
endpoint.

> **Note:** The v4 API is experimental and may change without notice.

## Routes

### `GET /v4/environments`

This will return all environments known to PuppetDB

#### URL Parameters

* `query`: Optional. A JSON array containing the query in prefix notation. If
  not provided, all results will be returned.

#### Available Fields

* `"name"`: matches environments of the given name

#### Operators

See [the Operators page](./operators.html)

#### Response format

The response is a JSON array of hashes of the form:

    {"name": <string>}

The array is unsorted.

#### Example

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v4/environments'

### `GET /v4/environments/<ENVIRONMENT>`

This will return the name of the environment if it currently exists in PuppetDB. This route also supports the same URL parameters and operators as the '/v4/environments' route above.

#### Response format

The response is a JSON hash of the form:

    {"name": <string>}

#### Example

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v4/environments/production'

### `GET /v4/environments/<ENVIRONMENT>/[events|facts|reports|resources]`

These routes are identical to issuing a request to
`/v4/[events|facts|reports|resources]`, with a query parameter of
`["=","environment","<ENVIRONMENT>"]`. All query parameters and route
suffixes from the original routes are supported. The result format is also
the same. Additional query parameters are ANDed with the environment
clause. See [/v4/events][events], [/v4/facts][facts],
[/v4/reports][reports] or [/v4/resources][resources] for
more info.

## Paging

This query endpoint supports paged results via the common PuppetDB paging
query parameters.  For more information, please see the documentation
on [paging][paging].
