# aem-loki-integrator

AEM bundle and content package that attach a Loki (loki4j) appender to the AEM
(Sling Commons Log) Logback runtime. Every runtime setting - URL, credentials,
labels, batching and logger routing - is driven by a single OSGi
configuration.

## Modules

| Module   | Artifact                          | Purpose                                                                                                                        |
|----------|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `core`   | `aem-loki-integrator.core`        | OSGi bundle. Registers an `org.apache.sling.commons.log.logback.ConfigProvider` and builds the Loki Logback fragment from OSGi config. |
| `loki4j` | `aem-loki-integrator.loki4j`      | OSGi fragment bundle of `org.apache.sling.commons.log` that embeds `loki-logback-appender` so its classes are visible to Logback.      |
| `all`    | `aem-loki-integrator.all`         | Container content package. Embeds both bundles under `/apps/aem-loki-integrator-packages/application/install` and installs the default OSGi config.  |

## Coordinates

* groupId: `com.adobexp.loki`
* artifactId (reactor): `aem-loki-integrator`
* version: `1.0.0-SNAPSHOT`
* core bundle Java package: `com.adobexp.aem.loki`

## Runtime layout in JCR

```
/apps/aem-loki-integrator-packages
    application/install
        aem-loki-integrator.core-<version>.jar
        aem-loki-integrator.loki4j-<version>.jar
    osgiconfig/config
        com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json
```

## OSGi configuration

PID: `com.adobexp.aem.loki.LogbackLokiBootstrap`

| Property             | Type       | Default                                                        | Notes                                                                                           |
|----------------------|------------|----------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `url`                | String     | `http://localhost:3100/loki/api/v1/push`                       | Loki push endpoint. Empty URL disables the appender.                                            |
| `username`           | String     | (empty)                                                        | HTTP basic auth username. Empty disables auth.                                                  |
| `password`           | Password   | (empty)                                                        | HTTP basic auth password. Empty disables auth.                                                  |
| `labels`             | String[]   | `app=aem`, `environment=LOCAL`, `tier=Author`, `host=${HOSTNAME}`, `level=%level` | One `key=value` per entry. Values may contain Logback conversion words and context properties. |
| `batchMaxItems`      | Integer    | `700`                                                          | Max log events per HTTP batch.                                                                  |
| `batchTimeoutMs`     | Integer    | `9000`                                                         | Max age of a batch (ms) before being pushed.                                                    |
| `sendQueueMaxBytes`  | Long       | `0`                                                            | In-memory send buffer size. `0` keeps loki4j's built-in default.                                |
| `verbose`            | Boolean    | `false`                                                        | Turn on loki4j verbose diagnostic output.                                                       |
| `messagePattern`     | String     | `*%level* [%thread] %logger{100} \| %msg %ex`                  | Logback encoder pattern for the Loki message body.                                              |
| `loggers`            | String[]   | `com.adobexp.aem:DEBUG`                                        | One `<package>:<LEVEL>` (or `=`) per entry. All listed loggers route to the `LOKI` appender.    |

## Local build & deploy

```bash
# Build every module
mvn -f aem-loki-integrator clean install

# Deploy the content package to a running local AEM (default :4502)
mvn -f aem-loki-integrator/all clean install \
    -PautoInstallPackage \
    -Daem.host=localhost -Daem.port=4502
```

After install, open `/system/console/slinglog` and `/system/console/configMgr`
to confirm the `com.adobexp.aem.loki.LogbackLokiBootstrap` configuration is
bound and the `LOKI` appender is attached to the loggers listed in the config.
