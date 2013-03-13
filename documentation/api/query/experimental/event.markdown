---
title: "PuppetDB 1.1 » API » Experimental » Querying Events"
layout: default
canonical: "/puppetdb/1.1/api/query/experimental/event.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[report]: ./report.html
[operator]: ../v2/operators.html

## Routes

### `GET /experimental/events`

#### Parameters

* `query`: Required. A JSON array of query predicates, in prefix form. (The standard `["<OPERATOR>", "<FIELD>", "<VALUE>"]` format.)

For example, for all events in the report with id
'38ff2aef3ffb7800fe85b322280ade2b867c8d27', the JSON query structure would be:

    ["=", "report", "38ff2aef3ffb7800fe85b322280ade2b867c8d27"]

##### Operators

The only available [OPERATOR][] is `=`.

##### Fields

The only available FIELD is `report`, which is the unique id of the report; this
is a hash built up from the contents of the report which allow us to distinguish
it from other reports.  These ids can be acquired via the [`/reports`][report]
query endpoint.


#### Response format

 The response is a JSON array of events that matched the input parameters.
 The events are sorted by their timestamps, in descending order:

    [
      {
        "old-value": "absent",
        "property": "ensure",
        "timestamp": "2012-10-30T19:01:05.000Z",
        "resource-type": "File",
        "resource-title": "/tmp/reportingfoo",
        "new-value": "file",
        "message": "defined content as '{md5}49f68a5c8493ec2c0bf489821c21fc3b'",
        "report": "38ff2aef3ffb7800fe85b322280ade2b867c8d27",
        "status": "success"
      },
      {
        "old-value": "absent",
        "property": "message",
        "timestamp": "2012-10-30T19:01:05.000Z",
        "resource-type": "Notify",
        "resource-title": "notify, yo",
        "new-value": "notify, yo",
        "message": "defined 'message' as 'notify, yo'",
        "report": "38ff2aef3ffb7800fe85b322280ade2b867c8d27",
        "status": "success"
      }
    ]


#### Example

[You can use `curl`][curl] to query information about events like so:

    curl -G -H "Accept: application/json" 'http://localhost:8080/experimental/events' --data-urlencode 'query=["=", "report", "38ff2aef3ffb7800fe85b322280ade2b867c8d27"]'
