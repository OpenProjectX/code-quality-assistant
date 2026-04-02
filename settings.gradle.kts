import java.io.File

//val restrictedNetworkMode = providers.gradleProperty("restrictedNetworkMode")
//    .map(String::toBooleanStrictOrNull)
//    .orElse(
//        providers.environmentVariable("RESTRICTED_NETWORK_MODE")
//            .map(String::toBooleanStrictOrNull)
//    )
//    .getOrElse(false)

fun localRepositoryDirectories(): List<File> {
    val configuredPaths = sequenceOf(
        providers.gradleProperty("localRepositoryPaths").orNull,
        providers.environmentVariable("LOCAL_REPOSITORY_PATHS").orNull
    )
        .filterNotNull()
        .flatMap { it.split(',').asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::File)
        .toList()

    return configuredPaths.distinctBy { it.absoluteFile.normalize() }
}

pluginManagement {
    repositories {
//        localRepositoryDirectories().forEach { repoDir ->
//            maven(url = repoDir.toURI())
//        }
        mavenCentral()
//        if (!restrictedNetworkMode) {
            gradlePluginPortal()
            maven(url = "https://repo.spring.io/plugins-release")
//        }
    }
}

dependencyResolutionManagement {
    repositories {
//        localRepositoryDirectories().forEach { repoDir ->
//            maven(url = repoDir.toURI())
//        }
        mavenCentral()
    }
}

rootProject.name = "ai-test-plugin"

val excludeProjects: String? by settings

val buildFiles = fileTree(rootDir) {
    val excludes = excludeProjects?.split(",")
    include("**/*.gradle", "**/*.gradle.kts")
    exclude(
        "build",
        "**/gradle",
        "settings.gradle",
        "settings.gradle.kts",
        "buildSrc",
        "/build.gradle",
        "/build.gradle.kts",
        ".*",
        "out"
    )
    exclude("**/grails3")
    if (!excludes.isNullOrEmpty()) {
        exclude(excludes)
    }
}

val rootDirPath = rootDir.absolutePath + File.separator
buildFiles.forEach { buildFile ->
    val isDefaultName = buildFile.name.startsWith("build.gradle")
    val isKotlin = buildFile.name.endsWith(".kts")

    if (isDefaultName) {
        val buildFilePath = buildFile.parentFile.absolutePath
        val projectPath = buildFilePath
            .replace(rootDirPath, "")
            .replace(File.separator, ":")

        println("Adding project $projectPath")
        include(projectPath)
    } else {
        val projectName = if (isKotlin) {
            buildFile.name.removeSuffix(".gradle.kts")
        } else {
            buildFile.name.removeSuffix(".gradle")
        }

        val projectPath = ":$projectName"
        println("Adding project $projectPath")
        include(projectPath)

        val project = findProject(projectPath)
        project?.name = projectName
        project?.projectDir = buildFile.parentFile
        project?.buildFileName = buildFile.name
    }
}


gradle.extra["isCi"] = System.getenv().containsKey("CI") ||
        System.getenv().containsKey("GITHUB_ACTIONS") ||
        System.getenv().containsKey("JENKINS_HOME")
