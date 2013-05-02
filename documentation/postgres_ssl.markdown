---
title: "PuppetDB 1.3 » Configuration » Using SSL with PostgreSQL"
layout: default
canonical: "/puppetdb/latest/postgres_ssl.html"
---

## Talking to PostgreSQL using SSL/TLS

If you want SSL/TLS-secured connectivity between PuppetDB and PostgreSQL, you can configure it by following the instructions below.

When configuring SSL you need to decide whether you will:

* Use a self-signed certificate on the DB server (for example, you can use the Puppet CA for this)
* Use a publicly signed certificate on the DB server

Both methodologies are valid, but while self-signed certificates are by far more common in the real world, these types of configurations must be setup with some care.

If you wish to use self-signed certificates then you have a few options:

* Place your private CA in a Java Keystore and tell Java to use that keystore for its system keystore
* Disable SSL verification

Each option has different impact on your configuration, so we will try to go into more detail below.

Before beginning, take a look at the documentation [Secure TCP/IP Connections with SSL](http://www.postgresql.org/docs/current/static/ssl-tcp.html) as this explains how to configure SSL on the server side in detail.

*Note:* At this point the documentation below only covers server-based SSL, client certificate support is not documented.

### Using Puppet Certificates with the Java Keystore

In this case we use the Puppet certificates to secure your PostgreSQL server. This has the following benefits:

* Since your using PuppetDB we can presume that your are using Puppet on each server, this means you can re-use the local Puppet Agent certificate for PostgreSQL.
* Since your local agents certificate must be signed for Puppet to work, most people already have a process for getting these signed which reduces the steps required compared to some other self-signed workflow.
* We already recommend this methodology for securing the HTTPS interface for PuppetDB, so less effort again.

To begin, configure your PostgreSQL server to use the hosts Puppet server certificate and key. The location of these files can be found by using the following commands:

    # Certificate
    puppet config print hostcert
    # Key
    puppet config print hostprivkey

These files will need to be copied to the relevant directories as specified by the PostgreSQL configuration items `ssl_cert_file` and `ssl_key_file` as explained in detail by the [Secure TCP/IP Connections with SSL](http://www.postgresql.org/docs/current/static/ssl-tcp.html) instructions.

You will also need to make sure the setting `ssl` is set to `on` in your `postgresql.conf`. Once this has been done, restart PostgreSQL.

Now continue by creating a truststore as specified in the setup instructions for PuppetDB. If you have installed PuppetDB using a package or you have already used the tool `puppetdb-ssl-setup`, this will most likely already exist in `/etc/puppetdb/ssl`.

You will then need to tell Java to use this truststore instead of the default system one by specifying values for the properties for `trustStore` and `trustStorePassword`. These properties can be applied by modifying your service settings for PuppetDB and appending the required settings to the JAVA_ARGS variable. In Redhat the path to this file is `/etc/sysconfig/puppetdb`, in Debian `/etc/default/puppetdb`. For example:

    # Modify this if you'd like to change the memory allocation, enable JMX, etc
    JAVA_ARGS="-Xmx192m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/puppetdb/puppetdb-oom.hprof -Djavax.net.ssl.trustStore=/etc/puppetdb/ssl/truststore.jks -Djavax.net.ssl.trustStorePassword=<PASSWORD>"

*Note:* Replace `<PASSWORD>` with the password found in `/etc/puppetdb/ssl/puppetdb_keystore_pw.txt`.

Once this is done, you need to modify the database JDBC connection URL in your PuppetDB configuration as follows:

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true
    username = <USERNAME>
    password = <PASSWORD>

Restart PuppetDB and monitor your logs for errors. If all goes well your connection should now be SSL.

### Placing your own self-signed CA in a Java Keystore

If you so desire you can follow the documentation provided in the [PostgreSQL JDBC SSL Client Setup](http://jdbc.postgresql.org/documentation/head/ssl-client.html) instructions. This talks about how to generate a brand new SSL certificate, key and CA. Make sure you place your signed certificate and private key in the locations specified by the `ssl_cert_file` and `ssl_key_file` locations, and that you change the `ssl` setting to `on` in your `postgresql.conf`.

Once this is done you must modify the JDBC url in the database configuration section for PuppetDB. For example:

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true
    username = <USERNAME>
    password = <PASSWORD>

Restart PuppetDB and monitor your logs for errors. If all goes well your connection should now be SSL.

### Setting up SSL with a publicly signed certificate on the DB server

First, obtain your signed certificate using the process required by your commercial Certificate Authority. If you don't want to pay for individual certificates for each server in your enterprise you can probably get away with using wildcards in the subject or CN for your certificate. While at the moment DNS resolution based on CN isn't tested using the default SSLSocketFactory, we can not know if this will change going forward, and therefore if wildcard support will be included.

Use the documentation [Secure TCP/IP Connections with SSL](http://www.postgresql.org/docs/current/static/ssl-tcp.html) as this explains how to configure SSL on the server in detail. Make sure you place your signed certificate and private key in the locations specified by the `ssl_cert_file` and `ssl_key_file` locations, and that you change the `ssl` setting to `on` in your `postgresql.conf`.

Because the JDBC PostgreSQL driver utilizes the Java's system keystore, and because the system keystore usually contains all public CA's there should be no trust issues with the client configuration, all you need to do is modify the JDBC url as provided in the database configuration section for PuppetDB.


For example:

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true
    username = <USERNAME>
    password = <PASSWORD>

Once this has been done, restart PuppetDB and monitor your logs for errors. If all goes well your connection should now be SSL.

### Disabling SSL Verification

So before you commence, this is not recommended if you are wishing to gain the higher level of security provided with an SSL connection. Disabling SSL verification effectively removes the ability for the SSL client to detect man-in-the-middle attacks.

If however you really want this, the methodology is to simply modify your JDBC URL in the database configuration section of PuppetDB as follows:

{% comment %}This code block broke Jekyll for some reason. I'm using a pre-formatted version of it instead of chasing down the problem. -NF{% endcomment %}

<pre><code>[database]
classname = org.postgresql.Driver
subprotocol = postgresql
subname = //&lt;HOST&gt;:&lt;PORT&gt;/&lt;DATABASE&gt;?ssl=true&amp;sslfactory=org.postgresql.ssl.NonValidatingFactory
username = &lt;USERNAME&gt;
password = &lt;PASSWORD&gt;
</code></pre>

Make this configuration change then restart PuppetDB and monitor your logs for errors. If all goes well your connection should now be SSL, and validation should be disabled.
