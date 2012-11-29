# Accessing the REST API via curl

Sometimes it can be useful to interact with the PuppetDB REST API via
a command-line tool such as `curl`.  This allows you to  experiment
with the queries with minimal ramp-up, which can be very handy if you are
prototyping an application, just need to peek at a particular piece of
data quickly, etc.

## Using `curl` from `localhost` (non-SSL)

If you're using the default puppetdb config, there's a vanilla HTTP port open
on port 8080 of the puppetdb server itself.  It's only open to connections
received from `localhost`, so you'll need to open a shell on that host.  Then
you can run commands like these:

    curl -H "Accept: application/json" 'http://localhost:8080/facts/<node>'
    curl -H "Accept: application/json" 'http://localhost:8080/metrics/mbean/java.lang:type=Memory'

For additional examples, please see the docs for the individual REST endpoints:

[Facts](facts.md)
[Nodes](node.md)
[Resources](resource.md)
[Status](status.md)
[Metrics](metrics.md)

## Using `curl` from remote hosts (SSL/HTTPS)

If you'd like to issue requests from other hosts besides the puppetdb server
(or if you've configured your puppetdb server to only open an HTTPS port, with
no HTTP port), you'll also need to point `curl` to your SSL certs in order
to connect successfully.  (The certs you use will need to be signed by the
puppet CA in order for PuppetDb to trust them.)

    curl -H "Accept: application/json" 'https://<your.puppetdb.server>:8081/facts/<node>' --cacert /etc/puppet/ssl/certs/ca.pem --cert /etc/puppet/ssl/certs/<node>.pem --key /etc/puppet/ssl/private_keys/<node>.pem

## Dealing with complex query strings

For some commands (e.g. 'nodes'), you may wish provide a query string that contains
characters such as `[` and `]`, which need to be URL-encoded.  To handle this,
you can use `curl`'s `--data-urlencode` option.  However, `curl` will normally
treat the presence of this option as an indication that it should send a `POST`
request instead of a `GET`.  Many of the puppetdb endpoints only accept `GET`
requests, so you'll need to additionally specify the `-G` or `--get` option to
tell `curl` to use `GET`.  Here's an example:

    curl -G -H "Accept: application/json" 'http://localhost:8080/nodes' --data-urlencode 'query=["=", ["fact", "kernel"], "Linux"]'





