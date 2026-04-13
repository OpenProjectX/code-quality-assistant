package org.openprojectx.ai.plugin.fakelogin

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

const val HTTPS_PORT = 8443

/** System-property key that response templates read the API key from. */
const val API_KEY_PROP = "fake.login.api.key"

/** Environment-variable name checked first when resolving the API key. */
const val API_KEY_ENV = "FAKE_LOGIN_API_KEY"

const val API_KEY_DEFAULT = "fake-dev-api-key-from-server"

fun main() {
    // Resolve API key: env var → system property → hard-coded default.
    // Determine the source BEFORE writing back, so the log message is accurate.
    val envValue  = System.getenv(API_KEY_ENV)
    val propValue = System.getProperty(API_KEY_PROP)
    val (apiKey, source) = when {
        envValue  != null -> envValue  to "env var $API_KEY_ENV"
        propValue != null -> propValue to "system property $API_KEY_PROP"
        else              -> API_KEY_DEFAULT to "built-in default"
    }
    // Pin the resolved value as a system property so the response template can
    // read it unconditionally via {{systemValue key='fake.login.api.key' type='PROPERTY'}}.
    System.setProperty(API_KEY_PROP, apiKey)

    val server = WireMockServer(
        wireMockConfig()
            .httpsPort(HTTPS_PORT)
            // Load stub mappings from  wiremock/mappings/*.json
            // and response bodies from wiremock/__files/*   on the classpath.
            .usingFilesUnderClasspath("wiremock")
            // Force HTTP/1.1 on TLS — HTTP/2 ALPN negotiation causes issues with
            // some clients (curl) on the self-signed cert used here.
            .http2TlsDisabled(true)
            // Enable Handlebars templating for all stubs (applies to __files/ bodies too).
            .globalTemplating(true)
            // Whitelist the property key used by the login-response template.
            .withPermittedSystemKeys(API_KEY_PROP)
    )

    server.start()

    println("[fake-login] WireMock HTTPS server running on https://127.0.0.1:$HTTPS_PORT")
    println("[fake-login] API key source: $source")
    println("[fake-login] API key value : $apiKey")
    println("[fake-login] Stubs loaded from classpath:wiremock/mappings/")
    println("[fake-login] Press Ctrl-C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("[fake-login] Shutting down WireMock server ...")
        server.stop()
    })

    Thread.currentThread().join()
}
