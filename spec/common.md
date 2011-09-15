# Common Behaviours and Background

## Read-Only RESTful Query Architecture assumptions

The CMDB RESTful API is a read-only, and query focused.  It fronts denormalized data stores designed to deliver extremely high query performance.

Operations that change the domain store, and consequently the data visible through the query API, are "eventually consistent": there may be a delay between the submission of the mutation command and the update of data accessible through the view.

## Failure and Retries

The CMDB MAY fail any operation, and expect the client to retry.  This allows more graceful degradation when faced with an overload situation, or for preserving data store coherence by refusing service during complex operations.

When instructed, the client SHOULD retry any operation when directed, using a truncated binary exponential backoff strategy; when you have had a request fail N times, retry after a delay between 0 and 2^N-1 * 0.01 seconds.

When N reaches 10, the client SHOULD probably give up, but MAY continue to retry with the same delay for up to 20 tries.  The client MUST abandon the request after a maximum of 20 tries.

This retry strategy is chosen to minimize lockstep retry operations, in order to probablistically maximize the number of clients that can success their operations under extreme load.

## URI Space

Resources accessible from the CMDB are addressed and identified by URI.  While the URI is generally predictable and obvious, the client SHOULD NOT generate the URI from whole cloth.

The CMDB MUST provide internal, navigable links between resources, and the client SHOULD use those to navigate the structure between entities.

## Media Types

In the CMDB, every resource you can read has its own media-type, indicating both the encoding and semantic significance of the data.  These types match the pattern <code>application/vnd.com.puppetlabs.<em>TYPE</em>+json</code>

Data format versioning, if required, will use a MIME attribute to match the higher versions: <code>application/vnd.com.puppetlabs.example+json; version=2</code>

The CMDB *MUST* provide representations in JSON, and MAY provide additional representations including user-focused HTML, YAML, or other forms for compatibility or compliance.

The JSON data formats are authoritative: other formats may not be full fidelity representations of the content, or may have other limitations, although they *SHOULD* be faithful to the original.

The CMDB *MUST* accept requests from the client in JSON, and MAY provide other input representations, including query parameters, where those make reasonable sense.

## Self-referencing Resources

Most resources include a `uri` field that provides a link that can be used to refresh the resource.  These SHOULD be present unless the entity cannot change, or cannot be refreshed.

The client MAY treat two resources as identical if the URI field is identical, but care should be taken that they may represent versions of the data at different times: the most recently retrieved data MUST be used if the two are treated as identical.

When treating resources as identical, the client SHOULD use standard HTTP mechanisms for freshness checking, such as the `If-Modified-Since` and `If-None-Match` / `ETag` headers, as well as cache controls, to validate the resource before reuse.


## Pagination

When a query returns multiple items, it is always paginated.  This ensures that a careless client request cannot fetch gigabytes of data out of the box.

*NOT USED*: We don't presently need this, though we anticipate something similar being required for the UI.  Instead of rushing into implementation too early we elect to delay until we have concrete consumers to work with.

REVISIT: Is this a standard format, which should have a MIME type to call its own?  I think so, and it then embeds some other type.

CMDB pagination uses the `limit`, `offset`, and `order` to support client side enumeration of data.  Each query request MAY include any, or all, of those keys to indicate the desired data range:

`offset`
: An integer; the CMDB MUST skip `offset` items from the start of the set.  An `offset` of 0 is equivalent to not giving any offset.  If the `offset` is greater than the end of the data, the CMDB MUST return a `204 (no content)` HTTP status, and an empty set of data with no `next` link.

`limit`
: An integer; the CMDB MUST return between zero and `limit` data items on this page.  The client MUST NOT interpret getting less than `limit` items of data, including getting zero items, as a signal that no more data is available.

`order`
: An array of strings; The CMDB MUST sort data by the key(s) specified, in the order they are in the array, or MUST return an HTTP status of `400 (bad syntax)` to signal that the request was not understood.  Each field name MAY be prefixed by a `+` or `-` sign to indicate ascending or descending order, and the CMDB MUST treat that in the same way as the field name itself.

Any field that contains invalid data (eg: non-integer limit and offset, or an unknown field name, or strange formatting) will get an HTTP response of `400 (bad syntax)`.  The CMDB MUST NOT ignore any part of the request and erturn results.


The CMDB MUST include links to the rest of the pagination as a `pages` set of links in the result.  Each link is to another part of the pagination, and the `rel` value determines the relationship:

`next`
: The URL for the immediate next page of results.  This MUST be present if there is more data, and MUST be absent if there are no more records.

`prev`
: The URL for the immediate previous page of results.  The SHOULD be present if there is data available before the first record.

`first`
: The URL for the very first page of results.  This MAY be available.

`last`
: The URL for the very last page of results.  This MAY be available.


Finally, the returned JSON MUST HAVE all the keys:

`uri`
: The URI that, if fetched, will refresh the current page of data.  The client MUST NOT use the values in the `uri` to infer state in the request.  This URI MAY be different from the URI submitted by the client in their initial request.

`pages`
: A map of links to other pages within the query.  The key is the `rel` value of the link, and the value a map with the details of the link.  This representation gives more immediate access to the required links to consumers.

`total`
: The total number of items currently found in the collection.

`offset`
: The offset of the first item in the current set.

`order`
: A string representing the order that data being returned from the collection.

`data`
: An array containing the actual data model items.  If not data rows are being returned on this page the CMDB MUST send an empty array to the client.


For example, for an offset of 10, and limit of 10, out of 50 total items in the example collection, you would get:

    {
      "uri": "/examples?offset=10&limit=10",
      "pages": {
        "next": { "href": "/examples?offset=20&limit=10", "rel": "next" },
        "prev": { "href": "/examples?offset=0&limit=10",  "rel": "prev" }
      },
      "total":  50,
      "offset": 10,
      "order":  ["+type, "+title"],
      "data": [
        // zero or more data items, as defined by the resource model.
      ]
    }


## Data Format Specific Conventions

### JSON Conventions

As far as possible, native JSON types are used exclusively.  In a limited number of areas additional semantics may be present, and the conventions for handling those are documented here to ensure coherence in the API.

#### Compatible Changes

Generally speaking, adding extra keys to a map is considered a compatible change, provided they do not change the overall semantics of the data type.

The client SHOULD NOT fail when a map has additional keys that were not present at the time it was built.

#### Date and Time

Dates are represented as strings, in "extended" [ISO 8601 format][8601] - they contain embedded, human-focused separators to make the format easier to consume by both sides.  The client MUST parse the full 8601 format.

The standard format of a date is `YYYY-MM-DD`; as per ISO 8601, a truncated version represents the full month, or full year.

The standard format of a time is `HH:MM:SS(Z|±hh[mm])`; the postfix Z indicates UTC, which the offset is given in hours and, optionally, minutes.

When a representation of a specific timestamp is required, the format is given as two distinct ISO 8601 fields, separated by a single space, in a single JSON string.  For example, the 2008 leap second in CST: `"2008-12-31 17:59:60-06"`

[8601]: http://en.wikipedia.org/wiki/ISO_8601

#### Sets

Sets are collections of unique values.  Ideally, they are used in situations where there is no need to recognize a difference between an arbitrary array of values and a set of values.

They are returned by the CMDB as an array of JSON data, and should be compared without regard to order.  The CMDB SHOULD return a sorted array at all times, but the client MUST NOT rely on that behaviour.

When submitting set data, the client MUST NOT send duplicate items.  If duplicates are present the CMDB SHOULD accept the request, and treat the duplicate items as a single item in the resulting set.

However, the CMDB MAY reject as invalid any submitted set with duplicate items, for any reason.

#### Links between Resources

When links are made between entities, the JSON representation is a map, with the significant keys modeled directly on the HTTP 5 `link` element:

`href`: MUST be the relative or absolute URL for the target entity.  This is processed using the same rules as a URL in an HTTP document.

`rel`: SHOULD be present, and contain the relationship between the current entity and the target entity.  Of these, `next` and `prev` are perhaps the most significant values. Where the `rel` is significant it will be documented in the resource description.

`type`: MAY be the (advisory) expected media type of the link target.  The client MUST use the actual type of the target for rendering, not this type, to avoid races between updates.

`title`: MAY be the human-readable description of the link relationship, and its target. The client MUST NOT use this for purposes other than display.



## HTTP Status Code Meanings

The CMDB uses HTTP status codes to signal specific state to the client.  When a code is not specified here it should be interpreted as specified in HTTP 1.1, but the CMDB MAY later impose more specific meanings on the value.

### 200 OK

The request completed successfully; here is your data.

### 204 No Content

The request completed successfully, but there was no data in it.  The CMDB SHOULD return structured content, specific to the user, in the body of the request.

### 301 Moved Permanently

The requested resource has a new URL, and the CMDB desires that the client always use the new URL to access it.

The client SHOULD NOT retry the request with the current URL, but SHOULD follow the redirection to the new location.

### 304 Not Modified

The resource has not changes since the client previously requested it.

### 307 Moved Temporarily

The requested resource has a temporary new URL, and the CMDB desires that the client use the new URL for this access only.

The client MUST NOT use the new location beyond the current request, and any associated retries of that request.  It SHOULD follow the redirection.

### 400 Bad Request

Some aspect of the request, typically a missing or invalid parameter, renders this request invalid.  The client MUST NOT retry the request as-is.

The CMDB SHOULD include a structured error notification in the body of the response, but it is not mandatory.

### 401 Unauthorized

You need to supply authorization to perform this operation, or your credentials are invalid.

The client MUST NOT retry without changing the (lack of) authentication in the request.

### 403 Forbidden

Your credentials were accepted and validated, but you do not have the privileges required to complete this operation.

The client MUST NOT retry this operation.

### 404 Not Found

The requested resource does not exist.

The client SHOULD NOT retry this operation, but it SHOULD query again later.

The CMDB MAY return a 404 response even when the client follows a URI supplied by the client, and the client MUST NOT infer that the resource will return, or that the request should be retried, because of this.

### 405 Method not allowed

The requested method is not accepted on this resource.

The CMDB SHOULD return structured information indicating what methods are supported.

The client MUST NOT infer the supported set of methods for other resources from this response.

### 406 Not Acceptable

The CMDB understood the requested media type(s) or Accept-* headers, but was unable to find a matching format or representation.

The client MUST NOT retry the request without altering the set of acceptable representations of the resource.

The CMDB MAY include structured information about the available representations of the resource.

### 415 Unsupported Media Type

The requested media type(s) in your Accept header, or some other Accept-* header, was not understood by the CMDB.  The server cannot determine if it can service this request or not, because it does not understand the requested constraints.

The client MUST NOT retry the request without altering the accepted media types, the set of Accept headers, or otherwise resolving the situation.

### 500 Internal Server Error

The request failed, due to some internal server condition.

The client MAY retry the request.

### 501 Not Implemented

Some aspect of your request (HTTP method, Accept-* headers, etc) was invalid, or the CMDB otherwise does not support the requested operation.

The client MUST NOT retry this request, but it MAY try the same request on the same resource at a future date.

### 503 Service Unavailable

The requested query is not currently available.  The client SHOULD retry this request.



