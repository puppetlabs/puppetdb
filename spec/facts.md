# Facts

Querying facts is accomplished by making an HTTP request to the
`/facts` REST endpoint.

# Query format

Facts are queried by making a request to a URL of the following form:

The HTTP request must conform to the following format:

* The URL requested is `/facts/<node>`

* A `GET` is used.

* There is an `Accept` header containing `application/json`.

The supplied `<node>` path component indicaates the certname for which
facts should be retrieved.

# Response format

    {"name": "<node>",
     "facts": {
         "<fact name>": "<fact value>",
         "<fact name>": "<fact value>",
         ...
        }
    }

If no facts are known for the supplied node, an HTTP 404 is returned.

# Example

[You can use `curl`](curl.md) to grab facts like so:

    curl -H "Accept: application/json" 'http://localhost:8080/facts/<node>'

where <node> is the certname of the host you wish to view the facts for.
