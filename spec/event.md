# Events

Querying events from reports is accomplished by making an HTTP request to the
`/events` REST endpoint.

# Query format

* The HTTP method must be `GET`.

* There must be an `Accept` header specifying `application/json`.

* The `query` parameter is a JSON array of query predicates, in prefix
  form, conforming to the format described below.

The `query` parameter is described by the following grammar:

    query: [ {match} {field} {value} ]
    field: string
    match: "="

`field` may be any of:

`report-id`: the unique id of the report; these can be acquired in the response
    to a `store report` command, or via the [`/reports`](report.md) query endpoint.

For example, for all events in the report with id
'74c355d0-18ac-4b69-9505-ec9ed675f556', the JSON query structure would be:

    ["=", "report-id", "74c355d0-18ac-4b69-9505-ec9ed675f556"]

# Response format

 The response is a JSON array of events that matched the input parameters.
 The events are sorted by their timestamps, in descending order:

`[
    {
      "old-value": "absent",
      "property": "ensure",
      "timestamp": "2012-10-30T19:01:05.000Z",
      "resource-type": "File",
      "resource-title": "/tmp/reportingfoo",
      "new-value": "file",
      "message": "defined content as '{md5}49f68a5c8493ec2c0bf489821c21fc3b'",
      "report-id": "74c355d0-18ac-4b69-9505-ec9ed675f556",
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
      "report-id": "74c355d0-18ac-4b69-9505-ec9ed675f556",
      "status": "success"
    }
  ]`


# Example

[You can use `curl`](curl.md) to query information about reports like so:

    curl -G -H "Accept: application/json" 'http://localhost:8080/v2/events' --data-urlencode 'query=["=", "report-id", "74c355d0-18ac-4b69-9505-ec9ed675f556"]'
