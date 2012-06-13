# Commands

Commands are the mechanism by which changes are made to PuppetDB's
model of a population. Commands are represented by `command objects`,
which have the following JSON wire format:

    {"command" "..."
     "version" 123
     "payload" <json object>}

`command` is a string identifier for the desired command.

`version` is a JSON integer describing what version of the given
command you're attempting to invoke.

`payload` must be a valid JSON string of any sort. It's up to an
individual handler function how to interpret that object.

The entire command MUST be encoded as UTF-8.

# Command submission

Commands are submitted via HTTP to the `/commands/` URL, and must
conform to the following rules:

* A `POST` is used

* There is a parameter, `payload`, that contains the entire command as
  outlined above.

* There is an `Accept` header that contains `application/json`.

* The POST body is url-encoded

* The content-type is `x-www-form-urlencoded`.

Optionally, there may be a parameter, `checksum`, that contains a SHA-1 hash of
the payload, and will be used for verification.

If a command has been successfully submitted, the submitter will
receive the following response:

* A response code of 200

* A content-type of `application/json`

* The response body is a JSON object, containing a single key 'uuid', whose
  value is a UUID corresponding to the submitted command. This can be used by
  clients to correlate submitted commands with server-side logs, for example.

# Command semantics

Commands are processed _asynchronously_. If PuppetDB returns a 200
when you submit a command, that only indicates that the command has
been _accepted_ for processing. There are no guarantees around when
that command will be processed, or that when it is processed it will
be successful.

Commands that fail processing will be stored in files in the "dead
letter office", located under the MQ data directory, in
`discarded/<command>`. These files contain the command and diagnostic
information that may be used to determine why the command failed to be
processed.

# Specific commands

## "replace catalog", version 1

The payload is expected to be a Puppet catalog conforming to the
[catalog wire format](catalog-wire-format.md).

## "replace facts", version 1

The payload is expected to be a Puppet catalog conforming to the
[fact wire format](fact-wire-format.md).
