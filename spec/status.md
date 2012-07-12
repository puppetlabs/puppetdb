# Status

## Node status

Node status can be queried by making a GET request to `/status/nodes/<node>`,
accepting JSON.

The format of the response is:

Node status information will be returned in a JSON hash of the form:

    {"name": <node>,
     "deactivated": <timestamp>,
     "catalog_timestamp": <timestamp>,
     "facts_timestamp": <timestamp>}

If the node is active, "deactivated" will be null. If a catalog or facts are
not present, the corresponding timestamps will be null.

If no information is known about the node, the result will be a 404 with a JSON
hash containing an "error" key with a message indicating such.

# Example

[You can use `curl`](curl.md) to query status like so:

    curl -H "Accept: application/json" 'http://localhost:8080/status/nodes/<node>'

where <node> is the certname of the host you wish to view the facts for.
