---
title: "Setting up SSL for PostgreSQL"
layout: default
canonical: "/puppetdb/latest/postgres_ssl.html"
---
# Setting up SSL for PostgreSQL
## Talking to PostgreSQL using SSL/TLS

This guide will help you configure SSL/TLS-secured connectivity between PuppetDB and PostgreSQL.

When configuring SSL, you need to decide whether you will use:

1. A self-signed certificate on the PuppetDB server (for example, the Puppet CA)
2. A publicly signed certificate on the PuppetDB server

Both methodologies are valid, but while self-signed certificates are far more common in the real world, this type of configuration must be set up with care.

Before beginning, take a look at PostgreSQL's [secure TCP/IP connections with SSL documentation](http://www.postgresql.org/docs/current/static/ssl-tcp.html), which explains in detail how to configure SSL on the server side.

*Note:* Our guide focuses on server-based SSL. Client certificate support is not documented at this time.

### Using Puppet Agent certificates for SSL

If you don't have a signed Puppet certificate on your PostgreSQL server, see the [ssl documentaion](https://jdbc.postgresql.org/documentation/head/ssl-client.html) for
alternate SSL connection options.

Using Puppet certificates to secure your PostgreSQL server has the following benefits:

* Because you are using PuppetDB, we can presume that you are using Puppet on each server. This means you can reuse the local Puppet agent certificate for PostgreSQL.
* Because your local Puppet agent's certificate must be signed for Puppet to work, you likely have an established workflow for getting these signed.
* We also recommend this methodology for securing the HTTPS interface for PuppetDB.
* You can remove the plaintext password from your PuppetDB config files if you
    also [configure database authorization using agent certificates](#using-puppet-agent-certificates-for-database-authorization)

To begin, configure your PostgreSQL server to use the host's Puppet server certificate and key. The location of these files can be found by using the following commands:

    # Certificate
    puppet config print hostcert
    # Key
    puppet config print hostprivkey

Copy these files to the relevant directories as specified by the PostgreSQL configuration items `ssl_cert_file` and `ssl_key_file`. This process is explained in detail [here](http://www.postgresql.org/docs/current/static/ssl-tcp.html).

Make sure that `ssl` is set to `on` in `postgresql.conf`, and then restart PostgreSQL.

After this is complete, modify the database JDBC connection URL in your PuppetDB configuration as follows:

    [database]
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true&sslfactory=org.postgresql.ssl.LibPQFactory&sslmode=verify-full&sslrootcert=/etc/puppetlabs/puppetdb/ssl/ca.pem
    username = <USERNAME>
    password = <PASSWORD>

Restart PuppetDB and monitor your logs for errors. Your connection should now
be SSL (but you will still be authorizing your database connection using a
plaintext password).

### Using Puppet Agent certificates for database authorization

To use Puppet's signed certificates to authenticate PuppetDB's database
connection (instead of using a password in the database config section), you
need to follow the above instructions for setting up an SSL connection between
PuppetDB and Postgres and then change the `pg_hba.conf` and `pg_ident.conf`
settings to allow your PuppetDB service to access the `puppetdb` database using
its certificate.  Also, your PostgreSQL server will need to be able to validate
the certificate of your PuppetDB server, so copy the `ca.pem` (which can be
found with `puppet config print localcacert` over to the directory of your
`ssl_cert_file` and `ssl_key_file`, and set the `ssl_ca_file` in your
`postgresql.conf`.

In `pg_hba.conf` you need to add one `hostssl` entry for each database user
configured. Starting in [PostgreSQL
14](https://www.postgresql.org/docs/14/release-14.html) the `clientcert=1`
option isn't supported and is instead replaced with `clientcert=verify-full`.
The `verify-full` option is first available in PostgreSQL 12.

```
# Allow certificate mapped connections to puppetdb as puppetdb (ipv6)
hostssl puppetdb     puppetdb     ::/0    cert    map=puppetdb-puppetdb-map clientcert=1

# Allow certificate mapped connections to puppetdb as puppetdb_migrator (ipv6)
hostssl puppetdb     puppetdb_migrator    ::/0    cert    map=puppetdb-puppetdb-migrator-map clientcert=1

# Allow certificate mapped connections to puppetdb as puppetdb_read (ipv6)
hostssl puppetdb     puppetdb_read        ::/0    cert    map=puppetdb-puppetdb-read-map clientcert=1
```

Then, in `pg_ident.conf` configure the certificate map from your certificate name to
the map names used in `pg_hba.conf`

```
puppetdb-puppetdb-map HOSTNAME puppetdb
puppetdb-puppetdb-migrator-map HOSTNAME puppetdb_migrator
puppetdb-puppetdb-read-map HOSTNAME puppetdb_read
```

Finally, configure PuppetDB's `subname` to include its private key and
certificate.

```
subname = //<HOST>:<PORT>/<DATABASE>?ssl=true&sslfactory=org.postgresql.ssl.LibPQFactory&sslmode=verify-full&sslrootcert=/etc/puppetlabs/puppetdb/ssl/ca.pem&sslkey=/tmp/private_key.pk8&sslcert=/etc/puppetlabs/puppetdb/ssl/public.pem
```

### Setting up SSL with a publicly signed certificate on the PuppetDB server

First, obtain your signed certificate using the process required by your commercial Certificate Authority. 

> **Note:** If you don't want to pay for individual certificates for each server in your enterprise, you can probably get away with using wildcards in the subject or CN for your certificate. Note, however, that while at the moment DNS resolution based on CN isn't tested using the default SSLSocketFactory, we do not know if this will change going forward, and therefore if wildcard support will be included.

Follow the documentation for [secure TCP/IP connections with SSL](http://www.postgresql.org/docs/current/static/ssl-tcp.html), which explains in detail how to configure SSL on the server. Make sure you place your signed certificate and private key in the locations specified by the `ssl_cert_file` and `ssl_key_file` locations, and that you change the `ssl` setting to `on` in your `postgresql.conf`. Don't forget to give the correct permissions for each file (such as `chmod 0600 file`). Otherwise, PostgreSQL will reject the key and cert files.

Because the JDBC PostgreSQL driver utilizes the Java's system KeyStore, and because the system KeyStore usually contains all public CAs, there should be no trust issues with the client configuration. Simply modify the JDBC URL as provided in the database configuration section for PuppetDB.

For example:

    [database]
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true&sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
    username = <USERNAME>
    password = <PASSWORD>

Restart PuppetDB and monitor your logs for errors. Your connection should now be SSL.

#### Using a custom Java keystore

If your CA cert is not in Java's default keystore, it may be necessary to create your own. Create a
TrustStore containing your CA certificate. If you have been using PuppetDB for a while, you might
already have such a file in `/etc/puppetdb/ssl/truststore.jks`. If not, the quickest way to create
this file is:

    $ sudo keytool -import -alias "My CA" -file /path/to/ca_crt.pem -keystore /etc/puppetdb/ssl/truststore.jks

Tell Java to use this TrustStore instead of the system's default by specifying values for the
properties for `trustStore` and `trustStorePassword`. These properties can be applied by modifying
your service settings for PuppetDB and appending the required settings to the JAVA_ARGS variable.
In Red Hat, the path to this file is `/etc/sysconfig/puppetdb`. In Debian, use
`/etc/default/puppetdb`. For example:

    # Modify this if you'd like to change the memory allocation, enable JMX, etc.
    JAVA_ARGS="-Xmx192m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/puppetlabs/puppetdb/puppetdb-oom.hprof -Djavax.net.ssl.trustStore=/etc/puppetlabs/puppetdb/ssl/truststore.jks -Djavax.net.ssl.trustStorePassword=<PASSWORD>"

*Note:* Replace `<PASSWORD>` with the password you used to create the KeyStore, or the one found in
`/etc/puppetdb/ssl/puppetdb_keystore_pw.txt`.

After this is complete, modify the database JDBC connection URL in your PuppetDB configuration as
the documentation describes above for publicly signed certificates.

### Disabling SSL verification

**Warning: This is not recommended.** SSL connections offer a higher level of security. Disabling SSL verification effectively removes the ability for the SSL client to detect man-in-the-middle attacks.

However, if you wish to disable SSL verification, you can do so by simply modifying your JDBC URL in the database configuration section of PuppetDB as follows:

{% comment %}This code block broke Jekyll for some reason. I'm using a pre-formatted version of it instead of chasing down the problem. -NF{% endcomment %}

<pre><code>[database]
subname = //&lt;HOST&gt;:&lt;PORT&gt;/&lt;DATABASE&gt;?ssl=true&amp;sslfactory=org.postgresql.ssl.NonValidatingFactory
username = &lt;USERNAME&gt;
password = &lt;PASSWORD&gt;
</code></pre>

Restart PuppetDB and monitor your logs for errors. Your connection should now be SSL, with validation disabled.
