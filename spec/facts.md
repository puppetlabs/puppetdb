# Facts

Querying facts is accomplished by making an HTTP request to the
`/facts` REST endpoint.

## v2

### Routes

#### `GET /facts`

This will return all facts matching the given query. If no query is supplied, all facts will be returned.

##### Parameters

  `query`: A JSON array containing the query in prefix notation.

##### Query paths

  `["fact", "name"]`: matches facts of the given name  
  `["fact", "value"]`: matches facts with the given value  
  `["node", "name"]`: matches facts for the given node  

##### Operators

    = > < >= <= and or not

##### Examples

  Get the operatingsystem fact for all nodes:

    curl -X GET -H 'Accept: application/json' http://puppetdb:8080/facts --data-urlencode 'query=["=", ["fact", "name"], "operatingsystem"]'

    [{"node": "a.example.com", "fact": "operatingsystem", "value": "Debian"},
     {"node": "b.example.com", "fact": "operatingsystem", "value": "RedHat"},
     {"node": "c.example.com", "fact": "operatingsystem", "value": "Darwin"},

  Get all facts for a single node:

    curl -X GET -H 'Accept: application/json' http://puppetdb:8080/facts --data-urlencode 'query=["=", ["node", "name"], "a.example.com"]'

    [{"node": "a.example.com", "fact": "operatingsystem", "value": "Debian"},
     {"node": "a.example.com", "fact": "ipaddress", "value": "192.168.1.105"},
     {"node": "a.example.com", "fact": "uptime_days", "value": "26 days"}]

#### `GET /facts/:node`

This will return all facts for the given node.

##### Examples

    curl -X GET -H 'Accept: application/json' http://puppetdb:8080/facts/a.example.com

    [{"node": "a.example.com", "fact": "operatingsystem", "value": "Debian"},
     {"node": "a.example.com", "fact": "ipaddress", "value": "192.168.1.105"},
     {"node": "a.example.com", "fact": "uptime_days", "value": "26 days"}]

### Request

All requests must accept `application/json`.

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result will be a JSON array, with one entry per fact. Each entry is of the form:

    {
      "node": <node name>,
      "fact": <fact name>,
      "value": <fact value>
    }


## v1

### Routes

#### `GET /facts/:node`

This will return all facts for the given node.

##### Examples

    curl -X GET -H 'Accept: application/json' http://puppetdb:8080/facts/a.example.com

    {"name": "a.example.com",
      "facts": {
         "operatingsystem": "Debian",
         "ipaddress": "192.168.1.105",
         "uptime_days": "26 days",
      }
    }

### Request

All requests must accept `application/json`.

### Response Format

Successful responses will be in `application/json`. Errors will be returned as
non-JSON strings.

The result is a JSON object containing two keys, "name" and "facts". The
"facts" entry is itself an object mapping fact names to values:

    {"name": "<node>",
      "facts": {
        "<fact name>": "<fact value>",
        "<fact name>": "<fact value>",
        ...
      }
    }
