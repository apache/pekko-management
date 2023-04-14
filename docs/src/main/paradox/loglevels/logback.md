# Logback

Dynamic Log Levels for Logback hooks into Pekko Management and provides a route where log levels can be read and set over HTTP.

## Project Info

@@project-info{ projectId="management-loglevels-logback" }

Requires @ref:[Pekko Management](../pekko-management.md) and that the application uses [Logback](http://logback.qos.ch) as logging backend.

@@dependency[sbt,Gradle,Maven] {
  symbol1=PekkoManagementVersion
  value1=$project.version$
  group=org.apache.pekko
  artifact=pekko-management-loglevels-logback_$scala.binary.version$
  version=PekkoManagementVersion
  group2=org.apache.pekko
  artifact2=pekko-management_$scala.binary.version$
  version2=PekkoManagementVersion
}

Pekko Management and `pekko-management-loglevels-logback` can be used with Pekko $pekko.version$ or later.
You have to override the following Pekko dependencies by defining them explicitly in your build and
define the Pekko version to the one that you are using. Latest patch version of Pekko is recommended and
a later version than $pekko.version$ can be used.

@@dependency[sbt,Gradle,Maven] {
  symbol=PekkoVersion
  value=$pekko.version$
  group=org.apache.pekko
  artifact=pekko-stream_$scala.binary.version$
  version=PekkoVersion
  group2=org.apache.pekko
  artifact2=pekko-slf4j_$scala.binary.version$
  version2=PekkoVersion
}

With Pekko Management started and this module on the classpath the module is automatically picked up and provides the following two HTTP routes:

### Reading Logger Levels

A HTTP `GET` request to `loglevel/logback?logger=[logger name]` will return the log level of that logger.

### Changing Logger Levels

Only enabled if `pekko.management.http.route-providers-read-only` is set to `false`. 

@@@ warning

If enabling this make sure to properly secure your endpoint with HTTPS and authentication or else anyone with access to the system could change logger levels and potentially do a DoS attack by setting all loggers to `TRACE`.

@@@

A HTTP `PUT` request to `loglevel/logback?logger=[logger name]&level=[level name]` will change the level of that logger on the JVM the `ActorSystem` runs on.

For example using curl:

```
curl -X PUT "http://127.0.0.1:6262/loglevel/logback?logger=com.example.MyActor&level=DEBUG"
```

#### Classic and Internal Pekko Logger Level

Internal Pekko actors and classic Pekko does logging through the built in API there is an [additional level of filtering](https://pekko.apache.org/docs/pekko/current/logging.html#slf4j) using the
`pekko.loglevel` setting. If you have not set `pekko.loglevel` to `DEBUG` (recommended) log entries from the classic logging API may never reach the logger backend at all.

The current level configured with `pekko.loglevel` can be inspected with a GET request to `loglevel/pekko`.

If management `read-only` is set to `false` PUT requests to `loglevel/pekko?level=[level name]` will dynamically change that.
Note that the allowed level for Pekko Classic logging is a subset of the loglevels supported by SLF4j, valid values are `OFF`, `DEBUG`, `INFO`, `WARNING` and `ERROR`.

For example using curl:

```
curl -X PUT "http://127.0.0.1:6262/loglevel/pekko?level=DEBUG"
```
