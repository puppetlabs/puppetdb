# Fact Names

The `/fact-names` endpoint can be used to retrieve all known fact names.

### Routes

#### `GET /fact-names`

This will return an alphabetical list of all known fact names, *including* those which are
known only for deactivated nodes.

##### Examples

    curl -X GET -H 'Accept: application/json' http://puppetdb:8080/v2/fact-names

    ["kernel", "operatingsystem", "osfamily", "uptime"]


### Request

All requests must accept `application/json`.

### Response Format

The response will be in `application/json`, and will contain an alphabetical
JSON array containing fact names. Each fact name will appear only once,
regardless of how many nodes have that fact.

    [<fact>, <fact>, ..., <fact>, <fact>]
