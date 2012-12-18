# Facts

### Routes

#### `GET /facts/:node`

This will return all facts for the given node.

##### Examples

    curl -X GET -H 'Accept: application/json' http://puppetdb:8080/facts/a.example.com

    {"name": "a.example.com",
      "facts": {
         "operatingsystem": "Debian",
         "ipaddress": "192.168.1.105",
         "uptime_days": "26 days"
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
