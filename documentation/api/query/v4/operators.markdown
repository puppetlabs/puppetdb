---
title: "PuppetDB 2.2 » API » v4 » Query Operators"
layout: default
canonical: "/puppetdb/latest/api/query/v4/operators.html"
---

[resources]: ./resources.html
[facts]: ./facts.html
[nodes]: ./nodes.html
[query]: ./query.html
[fact-contents]: ./fact-contents.html

PuppetDB's [query strings][query] can use several common operators.

## Binary Operators

Each of these operators accepts two arguments: a **field,** and a
**value.** These operators are **non-transitive:** their syntax must always be:

    ["<OPERATOR>", "<FIELD>", "<VALUE>"]

The available fields for each endpoint are listed in that endpoint's documentation.

### `=` (equality)

**Works with:** strings, numbers, timestamps, booleans, arrays, multi, path

**Matches if:** the field's actual value is exactly the same as the provided value.

* Most fields are strings.
* Some fields are booleans.
* Arrays match if any **one** of their elements match.
* Path matches are a special kind of array, and must be exactly matched with this operator.

### `>` (greater than)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is greater than the provided value.

### `<` (less than)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is less than the provided value.

### `>=` (greater than or equal to)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is greater than or equal to the provided value.

### `<=` (less than or equal to)

**Works with:** numbers, timestamps, multi

**Matches if:** the field is less than or equal to the provided value.

### `~` (regexp match)

**Works with:** strings, multi

**Matches if:** the field's actual value matches the provided regular expression. The provided value must be a regular expression represented as a JSON string:

* The regexp **must not** be surrounded by the slash characters (`/rexegp/`) that delimit regexps in many languages.
* Every backslash character **must** be escaped with an additional backslash. Thus, a sequence like `\d` would be represented as `\\d`, and a literal backslash (represented in a regexp as a double-backslash `\\`) would be represented as a quadruple-backslash (`\\\\`).

The following example would match if the `certname` field's actual value resembled something like `www03.example.com`:

    ["~", "certname", "www\\d+\\.example\\.com"]

> **Note:** Regular expression matching is performed by the database backend, and the available regexp features are backend-dependent. For best results, use the simplest and most common features that can accomplish your task. See the links below for details:
>
> * [PostgreSQL regexp features](http://www.postgresql.org/docs/9.3/static/functions-matching.html#POSIX-SYNTAX-DETAILS)
> * [HSQLDB (embedded database) regexp features](http://docs.oracle.com/javase/6/docs/api/java/util/regex/Pattern.html)

### '~>' (regexp array match)

**Works with:** paths

**Matches if:** the array matches using the regular expressions provided within in each element. Array indexes are coerced to strings.

The following example would match any network interface names starting with eth:

    ["~>", "path", ["networking", "eth.*", "macaddress"]]

If you want to match any index for an array path element, you can use regular expressions to do this as the element acts like a string:

    ["~>", "path", ["array_fact", ".*"]]

### `null?` (is null)

**Works with:** fields that may be null

**Matches if:** the field's value is null (when second argument is `true`) or the field is **not** null, i.e. has a real value (when second argument is `false`).

The following example would return events that do not have an associated line number:

    ["null?" "line" true]

Similarly, the below query would return events that do have a specified line number:

    ["null?" "line" false]


## Boolean Operators

Every argument of these operators should be a **complete query string** in its own right. These operators are **transitive:** the order of their arguments does not matter.

### `and`

**Matches if:** **all** of its arguments would match. Accepts any number of query strings as its arguments.

### `or`

**Matches if:** **at least one** of its arguments would match. Accepts any number of query strings as its arguments.

### `not`

**Matches if:** its argument **would not** match. Accepts a **single** query string as its argument.

## Projection Operators

### `extract`

To reduce the keypairs returned for each result in the response, you can use **extract**. Using extract outside of a subquery (which is discussed below) has the form below:

    ["extract" ["hash" "certname" "transaction_uuid"]
      ["=" "certname" "foo.com"]]

When only extracting a single column, the [] are optional

    ["extract" "transaction_uuid"
      ["=" "certname" "foo.com"]]

At this time extract must always have an expression to extract from, like `["=" "certname" "foo.com"]` above.

## Subquery Operators

Subqueries allow you to correlate data from multiple sources or multiple
rows. (For instance, a query such as "fetch the IP addresses of all nodes with
`Class[Apache]`" would have to use both facts and resources to return a list of facts.)

Subqueries are unlike the other operators listed above. They always appear together in the following form:

    ["in", ["<FIELDS>"], ["extract", ["<FIELDS>"], <SUBQUERY STATEMENT>] ]

That is:

* The `in` operator results in a complete query string. The `extract` operator and the subqueries do not.
* An `in` statement **must** contain one or more fields and an `extract` statement.
* An `extract` statement **must** contain one or more fields and a subquery statement.

These statements work together as follows (working "outward" and starting with the subquery):

* The subquery collects a group of PuppetDB objects (specifically, a group of [resources][], [facts][], [fact-contents][], or [nodes][]). Each of these objects has many **fields.**
* The `extract` statement collects the value of one or more **fields** across every object returned by the subquery.
* The `in` statement **matches** if its field values are present in the list returned by the `extract` statement.

Subquery | Extract | In
---------|---------|---
Every resource whose type is "Class" and title is "Apache." (Note that all resource objects have a `certname` field, among other fields.) | Every `certname` field from the results of the subquery. | Match if the `certname` field is present in the list from the `extract` statement.

The complete `in` statement described in the table above would match any object that shares a `certname` with a node that has `Class[Apache]`. This could be combined with a boolean operator to get a specific fact from every node that matches the `in` statement.

**Note:** Unlike in the v4 API, the v2 and v3 'in' and 'extract' operators do not permit vector-valued fields.

### `in`

An `in` statement constitutes a full query string, which can be used alone or as an argument for a [boolean operator](#boolean-operators).

"In" statements are **non-transitive** and take two arguments:

* The first argument **must** consist of one or more **fields** for the endpoint **being queried.**. This is a string or vector of strings.
* The second argument **must** be an **`extract` statement,** which acts as a list of possible values for the fields.

**Matches if:** the field values are included in the list of values created by the `extract` statement.

### `extract`

"Extract" statements are **non-transitive** and take two arguments:

* The first argument **must** be a valid set of **fields** for the endpoint **being subqueried** (see second argument). This is a string or vector of strings.
* The second argument **must** be a **subquery statement.**

As the second argument of an `in` statement, an `extract` statement acts as a list of possible values. This list is compiled by extracting the value of the requested field from every result of the subquery.

### Subquery Statements

A subquery statement **does not** constitute a full query string. It may only be used as the second argument of an `extract` statement.

Subquery statements are **non-transitive** and take two arguments:

* The first argument **must** be the **name** of one of the available subqueries (listed below).
* The second argument **must** be a **full query string** that makes sense for the endpoint being subqueried.

As the second argument of an `extract` statement, a subquery statement acts as a collection of PuppetDB objects. Each of the objects returned by the subquery has many fields; the `extract` statement takes the value of one field from each of those objects, and passes that list of values to the `in` statement that contains it.

### Available Subqueries

Each subquery acts as a normal query to one of the PuppetDB endpoints. For info on constructing useful queries, see the docs page for that endpoint.

The available subqueries are:

* `select-resources` (queries the [resources][] endpoint)
* `select-facts` (queries the [facts][] endpoint)
* `select-nodes` (queries the [nodes][] endpoint)
* `select-fact-contents` (queries the [fact-contents][] endpoint)

### Subquery Examples

This query string queries the `/facts` endpoint for the IP address of
all nodes with `Class[Apache]`:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select-resources",
            ["and",
              ["=", "type", "Class"],
              ["=", "title", "Apache"]]]]]]

This query string queries the `/nodes` endpoint for all nodes with `Class[Apache]`:

    ["in", "name",
      ["extract", "certname",
        ["select-resources",
          ["and",
            ["=", "type", "Class"],
            ["=", "title", "Apache"]]]]]]

This query string queries the `/facts` endpoint for the IP address of
all Debian nodes.

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select-facts",
            ["and",
              ["=", "name", "operatingsystem"],
              ["=", "value", "Debian"]]]]]]

This query string queries the `/facts` endpoint for uptime_hours of all nodes with
facts-environment `production`:

    ["and",
      ["=", "name", "uptime_hours"],
      ["in", "certname",
        ["extract", "certname",
          ["select-nodes",
            ["=", "facts-environment", "production"]]]]]

To find node information for a host that has a macaddress of `aa:bb:cc:dd:ee:00`, you could use this query on '/nodes':

    ["in", "certname",
      ["extract", "certname",
        ["select-fact-contents",
          ["and",
            ["=", "path", [ "networking", "eth0", "macaddresses", 0 ]],
            ["=", "value", "aa:bb:cc:dd:ee:00" ]]]]]

To exhibit a subquery using multiple fields, you could use the following
on '/facts' to list all top-level facts containing fact contents with paths
starting with "up" and value less than 100:

    ["in", ["certname", "name"],
      ["extract", ["certname", "name"],
        ["select-fact-contents",
          ["and",
            ["~>", "path", ["up.*"]],
            ["<", "value", 100]]]]]
