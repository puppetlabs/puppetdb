---
title: "PuppetDB 3.2: Commands endpoint"
layout: default
canonical: "/puppetdb/latest/api/command/v1/commands.html"
---

[factsv4]: ../../wire_format/facts_format_v4.html
[factsv5]: ../../wire_format/facts_format_v5.html
[catalogv6]: ../../wire_format/catalog_format_v6.html
[catalogv7]: ../../wire_format/catalog_format_v7.html
[catalogv8]: ../../wire_format/catalog_format_v8.html
[reportv5]: ../../wire_format/report_format_v5.html
[reportv6]: ../../wire_format/report_format_v6.html
[reportv7]: ../../wire_format/report_format_v7.html
[deactivatev3]: ../../wire_format/deactivate_node_format_v3.html
[deactivatev4]: ../../wire_format/deactivate_node_format_v4.html

Commands are used to change PuppetDB's model of a population. Commands are
represented by `command objects`, which have the following JSON wire format:

    {"command": "...",
     "version": 123,
     "payload": <json object>}

`command` is a string identifying the command.

`version` is a JSON integer describing what version of the given command you're
attempting to invoke. The version of the command also indicates the version of
the wire format to use for the command.

`payload` must be a valid JSON object of any sort. It's up to an individual
handler function to determine how to interpret that object.

The entire command **must** be encoded as UTF-8.

## Command submission

Commands must be submitted via HTTP to the `/pdb/cmd/v1` endpoint via one of two
mechanisms:

* Payload and parameters: This method entails POSTing the certname, command
name, command version, and optionally the checksum as parameters, with the POST
body containing the given command's wire format. This mechanism allows PuppetDB
to provide better validation and feedback at time of POSTing without inspecting
the command payload itself, and should be preferred over the alternative due to
lower memory consumption.

* Payload only (deprecated): This method entails POSTing a single JSON body
containing the certname, command name, and command version along side a
`payload` key valued with the given command's wire format. The checksum is
optionally provided as a parameter.

In either case, the checksum should contain a SHA-1 hash of the payload which
will be used for content verification with the server. When a command is
successfully submitted, the submitter will receive the following:

* A response code of 200.
* A content-type of `application/json`.
* A response body in the form of a JSON object, containing a single key, 'uuid',
  whose value is a UUID corresponding to the submitted command. This can be
  used, for example, by clients to correlate submitted commands with server-side
  logs.

The PuppetDB termini for Puppet masters use this command API to update facts,
catalogs, and reports for nodes, and will always include the checksum.

### Blocking command submission

>**Experimental feature:** This is an experimental feature, and it may be
>changed or removed at any time. Although convenient, it should be used with
>caution. Always prefer non-blocking command submission.

When submitting a command, you may specify the "secondsToWaitForCompletion"
query parameter. If you do, PuppetDB will block the request until the command
has been processed, or until the specified timeout has passed, whichever comes
first. The response will contain the following additional keys:

* `timed_out`: true when your timeout was hit before the command finished
  processing.

* `processed`: true when the command has been processed, successfully or not.
  Will be set to false if the timeout was hit first.

* `error`, `exception`: If the command was processed but an error occurred,
  these two fields provide the specifics of what went wrong.

## Command semantics

Commands are processed _asynchronously_. If PuppetDB returns a 200 when you
submit a command, that only indicates that the command has been _accepted_ for
processing. There are no guarantees as to when that command will be processed,
nor that when it is processed it will be successful.

Commands that fail processing will be stored in files in the "dead letter
office", located under the MQ data directory in `discarded/<command>`. These
files contain the command and diagnostic information that may be used to
determine why the command failed to be processed.

## List of commands

### "replace_catalog", version 8

* The nullable `catalog\_uuid` property has been added.
* The command name has been renamed to `replace_catalog` with an underscore
  instead of a space.

The payload is expected to be a Puppet catalog, as a JSON object, conforming
exactly to the [catalog wire format v8][catalogv8]. Extra or missing fields are
an error.

### "replace_facts", version 5

* The command name has been renamed to `replace_facts` with an underscore
  instead of a space.

See [fact wire format v5][factsv5] for more information on the payload of this
command.

### "deactivate_node", version 4

* The command name has been renamed to `deactivate_node` with an underscore
  instead of a space.

See [deactivate node wire format v4][deactivatev4] for more information on the
payload of this command.

### "store_report", version 7

* The nullable `catalog\_uuid`, `code\_id`, and `cached\_catalog\_status`
properties have been added.
* The command name has been renamed to `store_report` with an underscore instead
  of a space.

The payload is expected to be a report, containing events that occurred on
Puppet resources. It is structured as a JSON object, conforming to the
[report wire format v7][reportv7].

## Deprecated commands

### "replace catalog", version 7

* The nullable `code\_id` property has been added.

The payload is expected to be a Puppet catalog, as a JSON object, conforming
exactly to the [catalog wire format v7][catalogv7]. Extra or missing fields are
an error.

### "replace catalog", version 6

* All field names that were previously separated by dashes are now separated by
  underscores.

* The catalog `name` field has been renamed to `certname`.

The payload is expected to be a Puppet catalog, as a JSON object, conforming
exactly to the [catalog wire format v6][catalogv6]. Extra or missing fields are
an error.

### "store report", version 6

The version 6 store report command differs from previous versions by changing
from a `resource_events` property to a `resources` property. The
`resource_events` property was a merged version of `resources` and their
associated events `events`. This new version moves the command to use a similar
format to a raw Puppet report, with a list of `resources`, each with an `events`
property containing a list of the resource's events.

The payload is expected to be a report, containing events that occurred on
Puppet resources. It is structured as a JSON object, conforming to the
[report wire format v6][reportv6].

### "store report", version 5

The version 5 store report command differs from version 4 in the addition of a
"noop" flag, which is a Boolean indicating whether the report was produced by a
puppet run with `--noop`, as well as in the conversion of dash-separated fields
to underscored.

The payload is expected to be a report, containing events that occurred on
Puppet resources. It is structured as a JSON object, conforming to the
[report wire format v5][reportv5].

### "replace facts", version 4

* Similar to version 6 of replace catalog, previously dashed fields are now
  underscore-separated.

* The `name` field has been renamed to `certname`, for consistency.

See [fact wire format v4][factsv4] for more information on the payload of this
command.

### "deactivate node", version 3

* Previous versions of deactivate node required only the certname, as a raw JSON
  string. It is now formatted as a JSON map, and the `producer_timestamp`
  property has been added.

See [deactivate node wire format v3][deactivatev3] for more information on the
payload of this command.

## Examples using `curl`

To post a `replace facts` command you can use the following curl command:

    curl -X POST \
      -H 'Content-Type:application/json' \
      -H 'Accept:application/json' \
      -d '{"certname":"test1","environment":"DEV","values":{"myfact":"myvalue"},"producer_timestamp":"2015-01-01"}' \
      "http://localhost:8080/pdb/cmd/v1?command=replace_facts&version=4&certname=test1"

or equivalently (with the deprecated mechanism):

    curl -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -d '{"command":"replace facts","version":4,"payload":{"certname":"test1","environment":"DEV","values":{"myfact":"myvalue"},"producer_timestamp":"2015-01-01"}}' \
      http://localhost:8080/pdb/cmd/v1

An example of `deactivate node`:

    curl -X POST \
      -H 'Content-Type:application/json' \
      -H 'Accept:application/json' \
      -d '{"certname":"test1","producer_timestamp":"2015-01-01"}' \
      "http://localhost:8080/pdb/cmd/v1?certname=test1&command=deactivate_node&version=3"

or equivalently:

    curl -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -d '{"command":"deactivate node","version":3,"payload":{"certname":"test1","producer_timestamp":"2015-01-01"}}' \
      http://localhost:8080/pdb/cmd/v1
