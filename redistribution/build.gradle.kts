import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    base
}

val originalPluginZip = providers.gradleProperty("originalPluginZip")
val configFile = providers.gradleProperty("configFile")
val outputZip = providers.gradleProperty("outputZip")
val configJarName = providers.gradleProperty("configJarName").orElse("company-config.jar")

tasks.register("repackagePlugin") {
    group = "distribution"
    description = "Repackages an IntelliJ plugin ZIP with a classpath default config JAR."

    inputs.file(originalPluginZip.map { file(it) })
        .withPropertyName("originalPluginZip")
        .optional()
    inputs.file(configFile.map { file(it) })
        .withPropertyName("configFile")
        .optional()
    outputs.file(outputZip.orElse("build/distributions/ai-test-plugin-company.zip"))

    doLast {
        val sourceZip = requiredFile(originalPluginZip.orNull, "originalPluginZip")
        val yaml = requiredFile(configFile.orNull, "configFile")
        val targetZip = outputZip.orNull?.let { file(it) }
            ?: layout.buildDirectory.file("distributions/${sourceZip.nameWithoutExtension}-company.zip").get().asFile
        val jarName = configJarName.get()

        require(sourceZip.extension.equals("zip", ignoreCase = true)) {
            "originalPluginZip must point to a .zip file: ${sourceZip.absolutePath}"
        }
        require(yaml.extension in setOf("yaml", "yml")) {
            "configFile must point to a .yaml or .yml file: ${yaml.absolutePath}"
        }
        require(jarName.endsWith(".jar")) {
            "configJarName must end with .jar: $jarName"
        }

        targetZip.parentFile.mkdirs()
        val configJarBytes = createConfigJar(yaml)

        ZipFile(sourceZip).use { input ->
            val rootDir = detectPluginRoot(input)
            val libDirEntry = "$rootDir/lib/"
            val configJarEntry = "$libDirEntry$jarName"
            val written = linkedSetOf<String>()

            ZipOutputStream(targetZip.outputStream().buffered()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    if (entry.name == configJarEntry) {
                        return@forEach
                    }
                    output.putNextEntry(entry.copyForOutput())
                    if (!entry.isDirectory) {
                        input.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                    written += entry.name
                }

                if (libDirEntry !in written) {
                    output.putNextEntry(ZipEntry(libDirEntry))
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry(configJarEntry))
                output.write(configJarBytes)
                output.closeEntry()
            }
        }

        logger.lifecycle("Created redistributed plugin ZIP: ${targetZip.absolutePath}")
        logger.lifecycle("Injected config JAR: lib/$jarName")
    }
}

fun requiredFile(path: String?, propertyName: String): File {
    require(!path.isNullOrBlank()) {
        "Missing -P$propertyName=/path/to/file"
    }
    val file = file(path)
    require(file.isFile) {
        "-P$propertyName does not point to a file: ${file.absolutePath}"
    }
    return file
}

fun createConfigJar(configFile: File): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { jar ->
        jar.putNextEntry(ZipEntry("openprojectx/ai-test/"))
        jar.closeEntry()
        jar.putNextEntry(ZipEntry("openprojectx/ai-test/config.yaml"))
        configFile.inputStream().buffered().use { it.copyTo(jar) }
        jar.closeEntry()
    }
    return buffer.toByteArray()
}

fun detectPluginRoot(zipFile: ZipFile): String {
    val roots = zipFile.entries().asSequence()
        .map { it.name.trimStart('/') }
        .filter { it.isNotBlank() }
        .map { it.substringBefore('/') }
        .filter { it.isNotBlank() && it != "META-INF" }
        .toSet()

    require(roots.size == 1) {
        "Expected original plugin ZIP to contain one top-level plugin directory, found: ${roots.joinToString()}"
    }
    return roots.single()
}

fun ZipEntry.copyForOutput(): ZipEntry =
    ZipEntry(name).also { copy ->
        copy.comment = comment
        copy.method = method
        if (time >= 0) {
            copy.time = time
        }
        if (method == ZipEntry.STORED) {
            copy.size = size
            copy.compressedSize = compressedSize
            copy.crc = crc
        }
    }
