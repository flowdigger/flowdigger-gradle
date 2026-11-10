import org.gradle.api.GradleException
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    `maven-publish`
    signing
}

fun ProviderFactory.secret(
    gradlePropertyName: String,
    environmentVariableName: String
): Provider<String> = gradleProperty(gradlePropertyName)
    .orElse(environmentVariable(environmentVariableName))

val publishTarget = providers.gradleProperty("flowdigger.publish.target")
    .orElse(providers.environmentVariable("FLOWDIGGER_PUBLISH_TARGET"))
    .orElse("local-test")
    .map { it.trim() }

val supportedTargets = setOf("local-test", "maven-local", "central")
if (publishTarget.get() !in supportedTargets) {
    throw GradleException(
        "Unsupported flowdigger publish target '${publishTarget.get()}'. " +
            "Supported values: ${supportedTargets.joinToString(", ")}."
    )
}

val centralUsername = providers.secret("flowdigger.publish.username", "MAVEN_CENTRAL_USERNAME")
val centralPassword = providers.secret("flowdigger.publish.password", "MAVEN_CENTRAL_PASSWORD")
val signingKey = providers.secret("flowdigger.signing.key", "SIGNING_KEY")
val signingPassword = providers.secret("flowdigger.signing.password", "SIGNING_PASSWORD")

pluginManager.withPlugin("java") {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
}

val publishingExtension = extensions.getByType(PublishingExtension::class.java)

extensions.configure<PublishingExtension> {
    publications.withType(MavenPublication::class.java).configureEach {
        pom {
            name.convention(project.name)
            description.convention(
                providers.gradleProperty("projectDescription")
                    .orElse(project.provider { project.description ?: project.name })
            )
            url.convention(
                providers.gradleProperty("projectUrl")
                    .orElse("https://flowdigger.org")
            )

            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            scm {
                url.convention(providers.gradleProperty("scmUrl"))
                connection.convention(providers.gradleProperty("scmConnection"))
                developerConnection.convention(
                    providers.gradleProperty("scmDeveloperConnection")
                )
            }

            developers {
                developer {
                    id.convention(providers.gradleProperty("developerId"))
                    name.convention(providers.gradleProperty("developerName"))
                }
            }
        }
    }

    repositories {
        when (publishTarget.get()) {
            "local-test" -> {
                maven {
                    name = "localTest"
                    url = rootProject.layout.buildDirectory
                        .dir("test-repository")
                        .get()
                        .asFile
                        .toURI()
                }
            }

            "central" -> {
                maven {
                    name = "central"
                    val centralUrl = providers.gradleProperty("flowdigger.publish.central.url")
                        .orElse(
                            providers.environmentVariable("FLOWDIGGER_PUBLISH_CENTRAL_URL")
                        )
                        .orElse(
                            "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                        )
                    url = uri(centralUrl.get())
                    credentials {
                        username = centralUsername.orNull
                        password = centralPassword.orNull
                    }
                }
            }
        }
    }
}

extensions.configure<SigningExtension> {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishingExtension.publications)
    }
}

val validateCentralPublication = tasks.register("validateCentralPublication") {
    group = "verification"
    description = "Validates required metadata, credentials, and signing inputs for central publication."

    doLast {
        if (publishTarget.get() != "central") {
            return@doLast
        }

        fun required(label: String, value: String?) {
            if (value.isNullOrBlank()) {
                throw GradleException("$label is required for central publication.")
            }
        }

        val groupValue = project.group.toString()
        if (groupValue.isBlank() || groupValue == "unspecified") {
            throw GradleException("Project group must be set for central publication.")
        }

        val versionValue = project.version.toString()
        if (versionValue.isBlank() || versionValue == "unspecified") {
            throw GradleException("Project version must be set for central publication.")
        }
        if (versionValue.endsWith("-SNAPSHOT", ignoreCase = true)) {
            throw GradleException("Snapshot versions are not allowed for central publication.")
        }

        val mavenPublications = publishingExtension.publications.withType(MavenPublication::class.java)
        if (mavenPublications.isEmpty()) {
            throw GradleException("At least one MavenPublication is required for central publication.")
        }

        mavenPublications.forEach { publication ->
            required("Publication name", publication.name)
            required("POM name", publication.pom.name.orNull)
            required("POM description", publication.pom.description.orNull)
            required("Project URL", publication.pom.url.orNull)
        }

        required("License name", "Apache License, Version 2.0")
        required("License URL", "https://www.apache.org/licenses/LICENSE-2.0.txt")
        required("SCM URL", providers.gradleProperty("scmUrl").orNull)
        required("SCM connection", providers.gradleProperty("scmConnection").orNull)
        required(
            "SCM developer connection",
            providers.gradleProperty("scmDeveloperConnection").orNull
        )
        required("Developer ID", providers.gradleProperty("developerId").orNull)
        required("Developer name", providers.gradleProperty("developerName").orNull)
        required("Maven Central username", centralUsername.orNull)
        required("Maven Central password", centralPassword.orNull)
        required("Signing key", signingKey.orNull)
        required("Signing password", signingPassword.orNull)
    }
}

tasks.matching { task ->
    task.name.startsWith("publish") && task.name.contains("ToCentralRepository")
}.configureEach {
    dependsOn(validateCentralPublication)
}
