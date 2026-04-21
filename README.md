# aem-loki-integrator

AEM bundle and content package that attach a Loki (loki4j) appender to the AEM
(Sling Commons Log) Logback runtime. Every runtime setting - URL, credentials,
labels, batching and logger routing - is driven by a single OSGi
configuration.

The integration is **inactive by default**. The core component is marked
`ConfigurationPolicy.REQUIRE`, so the Loki appender only boots once a
configuration for `com.adobexp.aem.loki.LogbackLokiBootstrap` is present on
the running AEM instance. Remove the configuration and the appender stops;
redeploy the configuration and it resumes.

## Modules

| Module   | Artifact                     | Purpose                                                                                                                        |
|----------|------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `core`   | `aem-loki-integrator.core`   | OSGi bundle. Registers an `org.apache.sling.commons.log.logback.ConfigProvider` and builds the Loki Logback fragment from OSGi config. |
| `loki4j` | `aem-loki-integrator.loki4j` | OSGi fragment bundle of `org.apache.sling.commons.log` that embeds `loki-logback-appender` so its classes are visible to Logback.      |
| `all`    | `aem-loki-integrator.all`    | Content package. Embeds both bundles under `/apps/aem-loki-integrator-packages/application/install`. No OSGi config is shipped.        |

## Coordinates

* groupId: `com.adobexp.loki`
* artifactId (reactor): `aem-loki-integrator`
* version: `1.0.0-SNAPSHOT`
* core bundle Java package: `com.adobexp.aem.loki`

## Runtime layout in JCR

The `all` package only creates:

```
/apps/aem-loki-integrator-packages
    application/install
        aem-loki-integrator.core-<version>.jar
        aem-loki-integrator.loki4j-<version>.jar
```

The OSGi configuration is **not** part of this package. The consuming project
is expected to deploy the PID file under any of its own config folders, for
example:

```
/apps/<my-project>/osgiconfig/config/com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json
/apps/<my-project>/config.author/com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json
/apps/<my-project>/config.publish/com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json
```

A ready-to-copy template lives in [`example-osgi-config/`](example-osgi-config)
with blank credentials. Fill in `url`, `username`, `password` and any label
overrides, then drop the file into your project's `ui.config` (or equivalent)
module so it is installed at the desired run-mode.

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
| `loggers`            | String[]   | `com.adobexp:DEBUG`                                            | One `<package>:<LEVEL>` (or `=`) per entry. All listed loggers route to the `LOKI` appender.    |

While no configuration is bound, the component stays in the `unsatisfied`
state on `/system/console/components` and no Logback fragment is registered,
so the Loki appender is never created.

## Local build & deploy

```bash
# Build every module
mvn -f aem-loki-integrator clean install

# Deploy the content package to a running local AEM (default :4502)
mvn -f aem-loki-integrator/all clean install \
    -PautoInstallPackage \
    -Daem.host=localhost -Daem.port=4502
```

After install, open `/system/console/components?filter=com.adobexp.aem.loki`
to confirm the `LogbackLokiBootstrap` component is listed as **unsatisfied**
(no configuration yet). Drop
`example-osgi-config/com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json`
(with real credentials and URL) into any `/apps/**/config/` JCR folder - or
bind it transiently via `/system/console/configMgr` - and verify on
`/system/console/slinglog` that a `LOKI` appender is attached to the
configured loggers.
