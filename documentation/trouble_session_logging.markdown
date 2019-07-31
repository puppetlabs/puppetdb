---
title: "Troubleshooting: Session Logging"
layout: default
canonical: "/puppetdb/latest/trouble_session_logging.html"
---

What is Session Logging?
-----

PuppetDB's default log level only contains successfully negotiated HTTP or
HTTPS connections. Sessions that do not make it to the application-layer are
closed without a log entry. This is normally desired behavior but may inhibit
troubleshooting of sessions that are expected to work but fail, or unexpected
traffic that impairs the service but leaves no trace. By enabling session
logging, these failed connects can be seen and inspected.

Session logging can be very noisy and possibly impact availability of the
PuppetDB node. It is best enabled as needed and disabled after troubleshooting
is completed.

Foreground debugging
-----

Running PuppetDB in the foreground will enable all logging, including session
logging. It is extremely noisy but extremely simple to setup. Stop the
daemonized service, then run `puppetdb foreground --debug` as root. A
connection that fails to negotiate will show up in the output and look similar
to:

    2016-01-05 01:09:31,132 DEBUG [qtp296414558-71] [o.e.j.s.HttpConnection]
    javax.net.ssl.SSLHandshakeException: null cert chain
	    at sun.security.ssl.Handshaker.checkThrown(Handshaker.java:1431) ~[na:1.8.0_60]
	    at sun.security.ssl.SSLEngineImpl.checkTaskThrown(SSLEngineImpl.java:535) ~[na:1.8.0_60]
	    at sun.security.ssl.SSLEngineImpl.readNetRecord(SSLEngineImpl.java:813) ~[na:1.8.0_60]
	    at sun.security.ssl.SSLEngineImpl.unwrap(SSLEngineImpl.java:781) ~[na:1.8.0_60]
	    at javax.net.ssl.SSLEngine.unwrap(SSLEngine.java:624) ~[na:1.8.0_60]
	    at org.eclipse.jetty.io.ssl.SslConnection$DecryptedEndPoint.fill(SslConnection.java:516) ~[jetty-io-9.2.10.v20150310.jar:9.2.10.v20150310]
	    at org.eclipse.jetty.server.HttpConnection.onFillable(HttpConnection.java:239) ~[jetty-server-9.2.10.v20150310.jar:9.2.10.v20150310]
	    at org.eclipse.jetty.io.AbstractConnection$2.run(AbstractConnection.java:540) [jetty-io-9.2.10.v20150310.jar:9.2.10.v20150310]
	    at org.eclipse.jetty.util.thread.QueuedThreadPool.runJob(QueuedThreadPool.java:635) [jetty-util-9.2.10.v20150310.jar:9.2.10.v20150310]
	    at org.eclipse.jetty.util.thread.QueuedThreadPool$3.run(QueuedThreadPool.java:555) [jetty-util-9.2.10.v20150310.jar:9.2.10.v20150310]
	    at java.lang.Thread.run(Thread.java:745) [na:1.8.0_60]

When troubleshooting is complete, cancel the foreground job (commonly
ctrl+c/`^C`) and restart the daemonized service.

Daemonized Debugging
-----

To selectively enable session logging, or to make it part of your permanent
configuration, the file `logback.xml` inside the puppetdb directory
(e.g. `/etc/puppetlabs/puppetdb/logback.xml`) must be edited. Inside the
`configuration` element, add a `logger` element for
`org.eclipse.jetty.server.HttpConnection` with a level of `debug`:

    <configuration scan="true">
        # Existing content here
        <logger name="org.eclipse.jetty.server.HttpConnection" level="debug"/>
    </configuration>

Restart the service. Failed connections will now log to `puppetdb.log` or
`puppetdb-access.log`, depending on protocol, in the configured logdir (e.g.
`/var/log/puppetlabs/puppetdb/puppetdb.log` and
`/var/log/puppetlabs/puppetdb/puppetdb-access.log`).

Caveats
-----

PuppetDB will still only log sessions that make it to the java process.
Attempts that are blocked by a firewall such as iptables or directed to an
IP address that PuppetDB is not listening to will not be seen. Review the
firewall or OS logs for those session logs.

The additional logging, especially if the PuppetDB ports are made available to
the public, may have non-trivial implications for load on the node and hence
availability. This logging is only recommended during active troubleshooting,
not during normal operation.

