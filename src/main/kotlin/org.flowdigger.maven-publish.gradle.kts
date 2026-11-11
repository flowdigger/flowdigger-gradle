import org.gradle.api.GradleException
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

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
    .orElse(providers.gradleProperty("mavenCentralUsername"))
val centralPassword = providers.secret("flowdigger.publish.password", "MAVEN_CENTRAL_PASSWORD")
    .orElse(providers.gradleProperty("mavenCentralPassword"))
val signingKey = providers.secret("flowdigger.signing.key", "SIGNING_KEY")
    .orElse(providers.gradleProperty("signingKey"))
val signingPassword = providers.secret("flowdigger.signing.password", "SIGNING_PASSWORD")
    .orElse(providers.gradleProperty("signingPassword"))

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
    dependsOn(tasks.withType(GenerateMavenPom::class.java))

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
            val publicationName = publication.name.replaceFirstChar { it.uppercaseChar() }
            val generatePomTask = tasks.named(
                "generatePomFileFor${publicationName}Publication",
                GenerateMavenPom::class.java
            ).get()
            val pomFile = generatePomTask.destination
            if (!pomFile.exists()) {
                throw GradleException("Generated POM not found for publication '${publication.name}': $pomFile")
            }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)
            val projectElement = document.documentElement
            fun text(element: Element, tagName: String): String? =
                element.getElementsByTagName(tagName).item(0)?.textContent?.trim()

            required("POM name", text(projectElement, "name"))
            required("POM description", text(projectElement, "description"))
            required("Project URL", text(projectElement, "url"))

            val licenses = projectElement.getElementsByTagName("license")
            if (licenses.length == 0) {
                throw GradleException("At least one <license> entry is required in publication '${publication.name}'.")
            }
            val firstLicense = licenses.item(0) as Element
            required("License name", text(firstLicense, "name"))
            required("License URL", text(firstLicense, "url"))

            val scmEntries = projectElement.getElementsByTagName("scm")
            if (scmEntries.length == 0) {
                throw GradleException("A <scm> entry is required in publication '${publication.name}'.")
            }
            val scmElement = scmEntries.item(0) as Element
            required("SCM URL", text(scmElement, "url"))
            required("SCM connection", text(scmElement, "connection"))
            required("SCM developer connection", text(scmElement, "developerConnection"))

            val developers = projectElement.getElementsByTagName("developer")
            if (developers.length == 0) {
                throw GradleException("At least one <developer> entry is required in publication '${publication.name}'.")
            }
            val firstDeveloper = developers.item(0) as Element
            required("Developer ID", text(firstDeveloper, "id"))
            required("Developer name", text(firstDeveloper, "name"))
        }

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

val centralStagingRoot = layout.buildDirectory.dir("central-staging-repo")

val stageCentralBundleRepo = tasks.register("stageCentralBundleRepo", Sync::class.java) {
    dependsOn(tasks.named("publishToMavenLocal"))
    into(centralStagingRoot)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    notCompatibleWithConfigurationCache("Stages local Maven publications by traversing publication metadata at execution time.")
    val publicationPaths = linkedSetOf<String>()

    publishingExtension.publications.withType(MavenPublication::class.java).configureEach {
        val publicationPath = "${groupId.replace('.', '/')}/$artifactId/$version"
        if (publicationPaths.add(publicationPath)) {
            from(file("${System.getProperty("user.home")}/.m2/repository/$publicationPath")) {
                into(publicationPath)
            }
        }
    }

    doFirst {
        publicationPaths.forEach { publicationPath ->
            val localPublicationDir = file("${System.getProperty("user.home")}/.m2/repository/$publicationPath")
            if (!localPublicationDir.exists()) {
                throw GradleException("Expected local Maven publication directory not found: $localPublicationDir")
            }
        }
    }
}

val generateCentralBundleChecksums = tasks.register("generateCentralBundleChecksums") {
    dependsOn(stageCentralBundleRepo)
    notCompatibleWithConfigurationCache("Generates checksum files by scanning staged output directory at execution time.")

    doLast {
        val stagedRepoDir = centralStagingRoot.get().asFile
        if (!stagedRepoDir.exists()) {
            throw GradleException("Expected staged repository directory not found: $stagedRepoDir")
        }

        fun checksum(input: java.io.File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            input.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        stagedRepoDir.walkTopDown().forEach { candidate ->
            if (candidate.isFile && !candidate.name.endsWith(".md5") && !candidate.name.endsWith(".sha1")) {
                java.io.File(candidate.parentFile, "${candidate.name}.md5")
                    .writeText("${checksum(candidate, "MD5")}\n")
                java.io.File(candidate.parentFile, "${candidate.name}.sha1")
                    .writeText("${checksum(candidate, "SHA-1")}\n")
            }
        }
    }
}

tasks.register("packageCentralBundle", Zip::class.java) {
    dependsOn(generateCentralBundleChecksums)
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("central-bundle")
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))
    from(layout.buildDirectory.dir("central-staging-repo"))
}
