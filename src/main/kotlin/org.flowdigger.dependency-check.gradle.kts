pluginManager.withPlugin("org.owasp.dependencycheck") {
    val suppressionFile = file("dependency-check-suppressions.xml")
    val nvdApiKey = providers.gradleProperty("org.owasp.dependencycheck.nvd.api.key").orNull
        ?: System.getenv("NVD_API_KEY")

    extensions.getByName("dependencyCheck").withGroovyBuilder {
        setProperty("formats", listOf("HTML", "JSON", "XML"))
        setProperty("failBuildOnCVSS", 7.0f)
        setProperty("scanBuildEnv", false)
        setProperty(
            "skipConfigurations",
            listOf("checkstyle", "spotbugs", "spotbugsPlugins", "spotbugsSlf4j")
        )

        if (suppressionFile.exists()) {
            setProperty("suppressionFile", suppressionFile.absolutePath)
        }

        getProperty("analyzers").withGroovyBuilder {
            getProperty("ossIndex").withGroovyBuilder {
                setProperty("enabled", false)
            }
        }

        if (!nvdApiKey.isNullOrBlank()) {
            getProperty("nvd").withGroovyBuilder {
                setProperty("apiKey", nvdApiKey)
            }
        }
    }
}
