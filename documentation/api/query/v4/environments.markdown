---
title: "PuppetDB 1.6 » API » v4 » Querying Environments"
layout: default
canonical: "/puppetdb/latest/api/query/v4/environments.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html

Environments can be queried by making an HTTP request to the `/environments` REST
endpoint.

> **Note:** The v4 API is experimental and may change without notice.

## Routes

### `GET /v4/environments`

This will return all environments known to PuppetDB

#### Response format

The response is a JSON array of hashes of the form:

    {"name": <string>}

The array is unsorted.

#### Example

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v4/environments'

### `GET /v4/environments/<ENVIRONMENT>`

This will return the name of the environment if it currently exists in PuppetDB.

#### Response format

The response is a JSON hash of the form:

    {"name": <string>}

#### Example

[You can use `curl`][curl] to query information about nodes like so:

    curl 'http://localhost:8080/v4/environments/production'

### `GET /v4/environments/<ENVIRONMENT>/facts`

This route is identical to issuing a request to the '/v4/facts' route,
with a query parameter of ["=" "environment" "<ENVIRONMENT>"]. All
query parameters and route suffixes are the same as the facts route.
The result format is also the same as the facts route. Additional
query parameters are ANDed with the environment clause. See
[/v4/facts](./facts.html) for more info.

### `GET /v4/environments/<ENVIRONMENT>/resources`

This route is identical to issuing a request to the '/v4/resources' route,
with a query parameter of ["=" "environment" "<ENVIRONMENT>"]. All
query parameters and route suffixes are the same as the resources route.
The result format is also the same as the resources route. Additional
query parameters are ANDed with the environment clause. See
[/v4/resources](./resources.html) for more info.

### `GET /v4/environments/<ENVIRONMENT>/reports`

This route is identical to issuing a request to the '/v4/reports' route,
with a query parameter of ["=" "environment" "<ENVIRONMENT>"]. All
query parameters and route suffixes are the same as the reports route.
The result format is also the same as the reports route. Additional
query parameters are ANDed with the environment clause. See
[/v4/reports](./reports.html) for more info.

### `GET /v4/environments/<ENVIRONMENT>/events`

This route is identical to issuing a request to the '/v4/events' route,
with a query parameter of ["=" "environment" "<ENVIRONMENT>"]. All
query parameters and route suffixes are the same as the events route.
The result format is also the same as the events route. Additional
query parameters are ANDed with the environment clause. See
[/v4/events](./events.html) for more info.

## Paging

This query endpoint supports paged results via the common PuppetDB paging
query parameters.  For more information, please see the documentation
on [paging][paging].
