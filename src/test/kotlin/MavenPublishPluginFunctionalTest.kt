import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class MavenPublishPluginFunctionalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local-test publishes complete java repository`() {
        val projectDir = tempDir.resolve("java-consumer").toFile()
        projectDir.mkdirs()
        writeJavaConsumerProject(projectDir)

        val result = runner(projectDir, "publish").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":publish")?.outcome)

        val artifactId = projectDir.name
        val repoPath = projectDir.toPath()
            .resolve("build/test-repository/org/example/$artifactId/1.0.0")
        assertTrue(Files.exists(repoPath.resolve("$artifactId-1.0.0.jar")))
        assertTrue(Files.exists(repoPath.resolve("$artifactId-1.0.0-sources.jar")))
        assertTrue(Files.exists(repoPath.resolve("$artifactId-1.0.0-javadoc.jar")))
        assertTrue(Files.exists(repoPath.resolve("$artifactId-1.0.0.pom")))
        assertTrue(Files.exists(repoPath.resolve("$artifactId-1.0.0.module")))
    }

    @Test
    fun `local-test works without central credentials`() {
        val projectDir = tempDir.resolve("local-no-credentials").toFile()
        projectDir.mkdirs()
        writeJavaConsumerProject(projectDir)

        val result = runner(projectDir, "publish", "-Pflowdigger.publish.target=local-test").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":publish")?.outcome)
    }

    @Test
    fun `central fails early when credentials absent`() {
        val projectDir = tempDir.resolve("central-no-credentials").toFile()
        projectDir.mkdirs()
        writeJavaConsumerProject(projectDir)

        val failure = expectFailure(projectDir, "publish", "-Pflowdigger.publish.target=central")
        assertTrue(failure.contains("required for central publication"))
    }

    @Test
    fun `central fails early when signing missing`() {
        val projectDir = tempDir.resolve("central-no-signing").toFile()
        projectDir.mkdirs()
        writeJavaConsumerProject(projectDir)

        val failure = expectFailure(
            projectDir,
            "publish",
            "-Pflowdigger.publish.target=central",
            "-Pflowdigger.publish.username=user",
            "-Pflowdigger.publish.password=pass"
        )
        assertTrue(failure.contains("required for central publication"))
    }

    @Test
    fun `generated pom contains required metadata`() {
        val projectDir = tempDir.resolve("pom-metadata").toFile()
        projectDir.mkdirs()
        writeJavaConsumerProject(projectDir)

        runner(projectDir, "publish", "-Pflowdigger.publish.target=local-test").build()

        val artifactId = projectDir.name
        val pom = projectDir.toPath()
            .resolve("build/test-repository/org/example/$artifactId/1.0.0/$artifactId-1.0.0.pom")
            .toFile()
            .readText()

        assertTrue(pom.contains("<name>$artifactId</name>"))
        assertTrue(pom.contains("<description>Functional test publication</description>"))
        assertTrue(pom.contains("<url>https://example.org/java-consumer</url>"))
        assertTrue(pom.contains("<license>"))
        assertTrue(pom.contains("<scm>"))
        assertTrue(pom.contains("<developers>"))
    }

    @Test
    fun `non-java consumer publishes without java component`() {
        val projectDir = tempDir.resolve("non-java-consumer").toFile()
        projectDir.mkdirs()
        writeNonJavaConsumerProject(projectDir)

        val result = runner(projectDir, "publish", "-Pflowdigger.publish.target=local-test").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":publish")?.outcome)
    }

    @Test
    fun `configuration cache works for local target`() {
        val projectDir = tempDir.resolve("config-cache").toFile()
        projectDir.mkdirs()
        writeJavaConsumerProject(projectDir)

        val result = runner(
            projectDir,
            "publish",
            "-Pflowdigger.publish.target=local-test",
            "--configuration-cache"
        ).build()

        assertTrue(
            result.output.contains("Configuration cache entry stored") ||
                result.output.contains("Configuration cache entry reused")
        )
    }

    private fun writeJavaConsumerProject(projectDir: File) {
        writeCommonSettings(projectDir)
        writeFile(
            projectDir,
            "build.gradle.kts",
            """
            plugins {
                `java-library`
                `maven-publish`
                id("org.flowdigger.maven-publish")
            }

            group = "org.example"
            version = "1.0.0"
            description = "Functional test publication"

            publishing {
                publications {
                    register<MavenPublication>("mavenJava") {
                        from(components["java"])
                    }
                }
            }
            """.trimIndent()
        )
        writeFile(
            projectDir,
            "gradle.properties",
            """
            projectDescription=Functional test publication
            projectUrl=https://example.org/java-consumer
            scmUrl=https://github.com/example/java-consumer
            scmConnection=scm:git:https://github.com/example/java-consumer.git
            scmDeveloperConnection=scm:git:ssh://git@github.com/example/java-consumer.git
            developerId=tester
            developerName=Test User
            """.trimIndent()
        )
        writeFile(
            projectDir,
            "src/main/java/org/example/App.java",
            """
            package org.example;
            public class App {
                public String value() { return "ok"; }
            }
            """.trimIndent()
        )
    }

    private fun writeNonJavaConsumerProject(projectDir: File) {
        writeCommonSettings(projectDir)
        writeFile(
            projectDir,
            "build.gradle.kts",
            """
            plugins {
                base
                `maven-publish`
                id("org.flowdigger.maven-publish")
            }

            group = "org.example"
            version = "1.0.0"
            description = "Non Java consumer publication"

            val customArtifact = layout.buildDirectory.file("artifacts/payload.txt")
            val prepareArtifact = tasks.register("prepareArtifact") {
                outputs.file(customArtifact)
                doLast {
                    val out = customArtifact.get().asFile
                    out.parentFile.mkdirs()
                    out.writeText("payload")
                }
            }

            publishing {
                publications {
                    register<MavenPublication>("dist") {
                        artifact(customArtifact) {
                            extension = "txt"
                            builtBy(prepareArtifact)
                        }
                    }
                }
            }
            """.trimIndent()
        )
        writeFile(
            projectDir,
            "gradle.properties",
            """
            projectDescription=Non Java consumer publication
            projectUrl=https://example.org/non-java-consumer
            scmUrl=https://github.com/example/non-java-consumer
            scmConnection=scm:git:https://github.com/example/non-java-consumer.git
            scmDeveloperConnection=scm:git:ssh://git@github.com/example/non-java-consumer.git
            developerId=tester
            developerName=Test User
            """.trimIndent()
        )
    }

    private fun writeCommonSettings(projectDir: File) {
        val pluginProject = File(System.getProperty("user.dir")).absoluteFile
            .canonicalFile
            .invariantSeparatorsPath
        writeFile(
            projectDir,
            "settings.gradle.kts",
            """
            pluginManagement {
                includeBuild("$pluginProject")
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            
            rootProject.name = "${projectDir.name}"
            """.trimIndent()
        )
    }

    private fun expectFailure(projectDir: File, vararg args: String): String =
        runner(projectDir, *args).buildAndFail().output

    private fun runner(projectDir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("--stacktrace", *args)
            .forwardOutput()

    private fun writeFile(projectDir: File, relativePath: String, content: String) {
        val target = projectDir.toPath().resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, "$content\n")
    }
}
