# Metrics

Querying Grayskull metrics is accomplished by making an HTTP request
to paths under the `/metrics` REST endpoint.

# Listing available metrics

## Request format

To get a list of all available metric names:

* Request `/metrics/mbeans`.

* A `GET` request is used.

* There is an `Accept` header containing `application/json`.

## Response format

A JSON Object mapping a string to a string:

* The key is the name of a valid MBean

* The value is a URI to use for requesting that MBean's attributes

# Retrieving a specific metric

## Request format

To get the attributes of a particular metric:

* Request `/metrics/mbean/<name>`, where `<name>` is something
  returned in the list of available metrics specified above.

* A `GET` request is used.

* There is an `Accept` header containing `application/json`.

## Response format

A JSON Object mapping strings to (strings/numbers/booleans).

# Examples

    curl -v -H "Accept: application/json" 'http://localhost:8080/metrics/mbean/java.lang:type=Memory'
