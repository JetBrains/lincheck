/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust material for outbound TLS connections, built from one of the two supported CA sources:
 *
 * - the built-in truststore (`truststorePath == null`) — whatever the JVM was configured with
 *   (`javax.net.ssl.trustStore`, otherwise the bundled `cacerts`), so any registered CA is verified;
 * - an explicit CA truststore file — the self-signed CA used by tests.
 *
 * Loading a truststore parses and validates every certificate in it, so the resulting material is
 * memoized per `(path, password)` pair and shared by all HTTP and WebSocket connections of a process.
 */
object TlsTrust {

    private val cache = ConcurrentHashMap<String, TrustMaterial>()

    /** Socket factory for `wss://` WebSocket connections and `HttpsURLConnection`. */
    fun sslSocketFactory(truststorePath: String? = null, truststorePassword: String? = null): SSLSocketFactory =
        trustMaterial(truststorePath, truststorePassword).sslContext.socketFactory

    /** Trust manager for clients that take trust configuration directly, such as the Ktor CIO engine. */
    fun trustManager(truststorePath: String? = null, truststorePassword: String? = null): X509TrustManager =
        trustMaterial(truststorePath, truststorePassword).trustManager

    private fun trustMaterial(truststorePath: String?, truststorePassword: String?): TrustMaterial {
        val path = truststorePath?.trim()?.takeIf { it.isNotEmpty() }
        // ' ' is not a legal path character, so the two key parts cannot collide.
        return cache.computeIfAbsent("$path $truststorePassword") { load(path, truststorePassword) }
    }

    private fun load(truststorePath: String?, truststorePassword: String?): TrustMaterial {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        // A null KeyStore selects the JVM default truststore.
        trustManagerFactory.init(truststorePath?.let { loadKeyStore(it, truststorePassword) })
        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("No X509TrustManager for truststore ${truststorePath ?: "(JVM default)"}")
        val sslContext = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trustManager), null) }
        Logger.debug { "Loaded TLS trust material from ${truststorePath ?: "the JVM default truststore"}" }
        return TrustMaterial(sslContext, trustManager)
    }

    private class TrustMaterial(val sslContext: SSLContext, val trustManager: X509TrustManager)
}

/**
 * Loads a key- or truststore from [path], inferring its type from the file extension.
 *
 * @throws IllegalArgumentException if [path] does not name an existing file.
 */
fun loadKeyStore(path: String, password: String? = null): KeyStore {
    val file = File(path)
    require(file.isFile) { "Keystore not found: $path" }
    val keyStore = KeyStore.getInstance(keyStoreType(file))
    file.inputStream().use { keyStore.load(it, password?.toCharArray()) }
    return keyStore
}

/**
 * Guessed from the file extension rather than always using [KeyStore.getDefaultType]:
 * the JKS/PKCS12 compatibility mode that would otherwise paper over a mismatch
 * can be switched off via the `keystore.type.compat` security property.
 */
private fun keyStoreType(file: File): String = when (file.extension.lowercase()) {
    "p12", "pfx" -> "PKCS12"
    "jks" -> "JKS"
    else -> KeyStore.getDefaultType()
}
