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
    // WireMock standalone bundles Jetty, Jackson, and everything else needed.
    implementation("org.wiremock:wiremock-standalone:3.9.2")
}

/** Absolute path of the PID file; resolved eagerly so tasks only capture a plain String. */
val pidFilePath: String = layout.buildDirectory.file("fake-login-server.pid").get().asFile.absolutePath

/**
 * Starts the fake HTTPS login server (WireMock on port 8443) as a background process.
 * Stubs are loaded from src/main/resources/wiremock/mappings/.
 * Intended as a dependsOn for :plugin-idea:runIde during local development.
 *
 * The server PID is written to build/fake-login-server.pid so that
 * stopFakeLoginServer can terminate it cleanly.
 *
 * API key resolution order inside the server process (see Main.kt):
 *   1. env var   FAKE_LOGIN_API_KEY  (inherited from the shell automatically)
 *   2. -P / -D   fake.login.api.key  (forwarded below from Gradle / system properties)
 *   3. built-in default              "fake-dev-api-key-from-server"
 */
tasks.register("startFakeLoginServer") {
    group = "application"
    description = "Starts the fake HTTPS login server (WireMock, port 8443) as a background process"
    dependsOn("jar")

    // ── Resolve at configuration time (required for configuration cache) ──────
    // Provider/FileCollection values are config-cache serialisable; plain
    // project/tasks/configurations references captured inside doLast are not.
    val apiKeyProp = "fake.login.api.key"
    val resolvedApiKey: String? = providers.gradleProperty(apiKeyProp).orNull
        ?: providers.systemProperty(apiKeyProp).orNull

    val jarProvider: Provider<RegularFile> = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtimeClasspath: FileCollection = configurations["runtimeClasspath"]

    doLast {
        // ── Skip if already running ───────────────────────────────────────────
        // Attempt to bind the port: if it throws BindException something is
        // already listening there (a previous run), so we skip silently.
        val httpsPort = 8443
        try {
            ServerSocket(httpsPort).close()
        } catch (_: BindException) {
            logger.lifecycle("Fake login server already running on port $httpsPort — skipping.")
            return@doLast
        }

        // ── Java executable ────────────────────────────────────────────────────────
        // Use the same JDK that is running the Gradle daemon so we don't depend on
        // 'java' being on PATH (it often isn't inside IDE-launched Gradle builds).
        val javaExec = File(System.getProperty("java.home"), "bin/java").absolutePath

        // ── Classpath ──────────────────────────────────────────────────────────────
        val jarFile = jarProvider.get().asFile
        val classpath = (runtimeClasspath.files + setOf(jarFile))
            .joinToString(File.pathSeparator) { it.absolutePath }

        // ── JVM flags to forward ──────────────────────────────────────────────────
        // Forward fake.login.api.key if it was passed as a Gradle property (-P) or
        // system property (-D) so the server process picks up the right API key.
        val forwardedProps = buildList {
            if (resolvedApiKey != null) add("-D$apiKeyProp=$resolvedApiKey")
        }

        // ── Launch ────────────────────────────────────────────────────────────────
        val command = buildList {
            add(javaExec)
            addAll(forwardedProps)
            add("-cp")
            add(classpath)
            add("org.openprojectx.ai.plugin.fakelogin.MainKt")
        }

        logger.lifecycle("Starting fake login server:")
        logger.lifecycle("  exec : $javaExec")
        if (forwardedProps.isNotEmpty())
            logger.lifecycle("  props: ${forwardedProps.joinToString(" ")}")

        val process = ProcessBuilder(command)
            .inheritIO()          // stdout/stderr appear in the Gradle console
            .start()

        logger.lifecycle("Fake login server starting (PID ${process.pid()}) on https://127.0.0.1:$httpsPort ...")
        Thread.sleep(2000)
        if (!process.isAlive) {
            error("Fake login server exited early (exit code ${process.exitValue()})")
        }

        // Persist the PID so stopFakeLoginServer can find the process later.
        File(pidFilePath).writeText(process.pid().toString())
        logger.lifecycle("Fake login server is up (PID ${process.pid()}, PID file: $pidFilePath).")
    }
}

/**
 * Stops the fake HTTPS login server that was started by startFakeLoginServer.
 * Reads the PID from build/fake-login-server.pid and sends SIGTERM, waiting up
 * to 5 seconds before escalating to SIGKILL.
 */
tasks.register("stopFakeLoginServer") {
    group = "application"
    description = "Stops the fake HTTPS login server started by startFakeLoginServer"

    doLast {
        val pidFileRef = File(pidFilePath)
        val pid = pidFileRef.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        if (pid == null) {
            logger.lifecycle("No PID file found at $pidFilePath — server may not be running.")
            return@doLast
        }
        try {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle == null || !handle.isAlive) {
                logger.lifecycle("Fake login server (PID $pid) is not running.")
            } else {
                logger.lifecycle("Stopping fake login server (PID $pid) ...")
                handle.destroy()           // SIGTERM — lets WireMock flush and exit cleanly
                val exited = handle.onExit().get(5, TimeUnit.SECONDS) != null
                if (!exited) {
                    logger.warn("Fake login server did not exit within 5 s — sending SIGKILL.")
                    handle.destroyForcibly()
                } else {
                    logger.lifecycle("Fake login server stopped.")
                }
            }
        } catch (_: Exception) {
            logger.warn("Could not stop fake login server (PID $pid) — it may have already exited.")
        } finally {
            pidFileRef.delete()
        }
    }
}
