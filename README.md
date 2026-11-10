# flowdigger-gradle

Owns Gradle build and publishing semantics.

## Plugin

- **ID:** `org.flowdigger.maven-publish`
- **Type:** precompiled Kotlin script convention plugin
- **Location:** `src/main/kotlin/org.flowdigger.maven-publish.gradle.kts`
- **ID:** `org.flowdigger.dependency-check`
- **Type:** precompiled Kotlin script convention plugin
- **Location:** `src/main/kotlin/org.flowdigger.dependency-check.gradle.kts`

## Consumer usage

Released plugin:

```kotlin
plugins {
    id("org.flowdigger.maven-publish") version "1.0.0"
}
```

Local composite development:

```kotlin
pluginManagement {
    includeBuild("../flowdigger-gradle")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
plugins {
    id("org.flowdigger.maven-publish")
    id("org.owasp.dependencycheck") version "13.0.0"
    id("org.flowdigger.dependency-check")
}
```

## Publish target selection

Target resolution order: Gradle property first, environment variable second.

| Purpose | Gradle property | Environment variable |
|---|---|---|
| Target | `flowdigger.publish.target` | `FLOWDIGGER_PUBLISH_TARGET` |
| Central username | `flowdigger.publish.username` | `MAVEN_CENTRAL_USERNAME` |
| Central password | `flowdigger.publish.password` | `MAVEN_CENTRAL_PASSWORD` |
| Signing key | `flowdigger.signing.key` | `SIGNING_KEY` |
| Signing password | `flowdigger.signing.password` | `SIGNING_PASSWORD` |

Supported targets:

- `local-test` (default)
- `maven-local`
- `central`

## Verification

```bash
./gradlew test --tests MavenPublishPluginFunctionalTest
./gradlew validatePlugins
```
