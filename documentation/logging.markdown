---
title: "Logging configuration"
layout: default
---
# Logging configuration
## Structured logging

PuppetDB uses logback, a standard Java logging library. In certain subsystems,
namely HA, we provide extended structured logging information. By configuring
logback appropriately, you get JSON-formatted log messages with event-specific
fields in each message.

## Common fields

If you use the recommended logger configuration, as described below, you will
see the following fields in each JSON log message:

* @timestamp
* message
* logger_name
* thread_name
* level
* level_value (numeric, suitable for sorting)
* stack_trace

Additional relevant fields may be added in any given message, but this base set
will always be present.

## JSON text

If you want to log JSON data where you would otherwise log regular text, replace the 'encoder' element in
your logback.xml with this one:

    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
      <providers>
        <timestamp/>
        <message/>
        <loggerName/>
        <threadName/>
        <logLevel/>
        <logLevelValue/>
        <stackTrace/>
        <logstashMarkers/>
      </providers>
    </encoder>

Even though this says 'logstash' on it, it works completely independently from
any log aggregation system. The final `<logstashMarkers/>` element inserts our
custom properties into each JSON message.

## Logback integration

You can also log directly to logstash with an appender configured like this:

    <appender name="stash" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
      <remoteHost>logging.dev</remoteHost>
      <port>4560</port>

      <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
        <providers>
          <timestamp/>
          <message/>
          <loggerName/>
          <threadName/>
          <logLevel/>
          <logLevelValue/>
          <stackTrace/>
          <logstashMarkers/>
        </providers>
      </encoder>
    </appender>

You will also need to add a reference to the appender from the `<root>` element:

    <root>
      ...
      <appender-ref ref="stash" />
    </root>

## References

These example configurations should get you started. For more advanced
scenarios, the respective tools have good documentation.

* Logstash Appender: https://github.com/logstash/logstash-logback-encoder#tcp

* JSON Encoder: https://github.com/logstash/logstash-logback-encoder#composite_encoder

* Logback configuration: http://logback.qos.ch/manual/configuration.html
