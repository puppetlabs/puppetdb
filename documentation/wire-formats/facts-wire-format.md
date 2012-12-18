## Fact interchange format

Facts are represented as JSON. Unless otherwise noted, `null` is not
allowed anywhere in the set of facts.

    {"name": <string>,
     "values": {
         <string>: <string>,
         ...
         }
    }

The `"name"` key is the certname the facts are associated with.

The `"values"` key points to a JSON _Object_ that represents the set
of facts. Each key is the fact name, and the value is the fact value.

Fact names and value MUST be strings.

### Encoding

The entire fact set is expected to be valid JSON, which implies UTF-8
encoding.


