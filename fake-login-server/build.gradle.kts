import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.net.BindException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

plugins {
    application
}

application {
    mainClass.set("org.openprojectx.ai.plugin.fakelogin.MainKt")
}

dependencies {
    implementation("org.wiremock:wiremock-standalone:3.9.2")
}

abstract class StartFakeLoginServerTask : DefaultTask() {
    @get:InputFile
    abstract val serverJar: RegularFileProperty

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val pidFile: RegularFileProperty

    @get:Input
    abstract val apiKeyPropertyName: Property<String>

    @get:Input
    @get:Optional
    abstract val apiKeyValue: Property<String>

    @TaskAction
    fun start() {
        val httpsPort = 8443
        try { ServerSocket(httpsPort).close() } catch (_: BindException) {
            logger.lifecycle("Fake login server already running on port $httpsPort — skipping.")
            return
        }

        val javaExec = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = (runtimeClasspath.files + setOf(serverJar.get().asFile)).joinToString(java.io.File.pathSeparator) { it.absolutePath }
        val forwardedProps = buildList {
            val value = apiKeyValue.orNull
            if (!value.isNullOrBlank()) add("-D${apiKeyPropertyName.get()}=$value")
        }
        val command = buildList {
            add(javaExec); addAll(forwardedProps); add("-cp"); add(classpath); add("org.openprojectx.ai.plugin.fakelogin.MainKt")
        }
        val process = ProcessBuilder(command).inheritIO().start()
        logger.lifecycle("Fake login server starting (PID ${process.pid()}) on https://127.0.0.1:$httpsPort ...")
        Thread.sleep(2000)
        if (!process.isAlive) error("Fake login server exited early (exit code ${process.exitValue()})")
        pidFile.get().asFile.writeText(process.pid().toString())
        logger.lifecycle("Fake login server is up (PID ${process.pid()}, PID file: ${pidFile.get().asFile.absolutePath}).")
    }
}

abstract class StopFakeLoginServerTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val pidFile: RegularFileProperty

    @TaskAction
    fun stopServer() {
        val pidFileRef = pidFile.get().asFile
        val pid = pidFileRef.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        if (pid == null) {
            logger.lifecycle("No PID file found at ${pidFileRef.absolutePath} — server may not be running.")
            return
        }
        try {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle == null || !handle.isAlive) {
                logger.lifecycle("Fake login server (PID $pid) is not running.")
            } else {
                logger.lifecycle("Stopping fake login server (PID $pid) ...")
                handle.destroy()
                val exited = handle.onExit().get(5, TimeUnit.SECONDS) != null
                if (!exited) handle.destroyForcibly() else logger.lifecycle("Fake login server stopped.")
            }
        } catch (_: Exception) {
            logger.warn("Could not stop fake login server (PID $pid) — it may have already exited.")
        } finally { pidFileRef.delete() }
    }
}

val apiKeyProp = "fake.login.api.key"
val resolvedApiKey: String? = providers.gradleProperty(apiKeyProp).orNull ?: providers.systemProperty(apiKeyProp).orNull

tasks.register<StartFakeLoginServerTask>("startFakeLoginServer") {
    group = "application"
    description = "Starts the fake HTTPS login server (WireMock, port 8443) as a background process"
    dependsOn("jar")
    serverJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeClasspath.from(configurations.named("runtimeClasspath"))
    pidFile.set(layout.buildDirectory.file("fake-login-server.pid"))
    apiKeyPropertyName.set(apiKeyProp)
    apiKeyValue.set(resolvedApiKey)
}

tasks.register<StopFakeLoginServerTask>("stopFakeLoginServer") {
    group = "application"
    description = "Stops the fake HTTPS login server started by startFakeLoginServer"
    pidFile.set(layout.buildDirectory.file("fake-login-server.pid"))
}
