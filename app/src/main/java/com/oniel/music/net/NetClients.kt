package com.oniel.music.net

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * One OkHttp client for the whole app — Jellyfin API, presence socket,
 * images (Coil) and audio streaming (ExoPlayer's OkHttpDataSource) — so
 * the built-in Tailscale routing applies everywhere.
 */
object NetClients {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(TailnetProxySelector)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Sends tailnet hosts through the built-in Tailscale's SOCKS5 proxy;
 * everything else (home-Wi-Fi addresses, the internet) connects directly.
 * When built-in Tailscale is off, nothing is proxied (the Tailscale app's
 * VPN, if any, works as before).
 */
object TailnetProxySelector : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host
        val port = Tailscale.socksPort
        if (host != null && port > 0 && Tailscale.isRunning && isTailnetHost(host)) {
            return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    /**
     * Tailnet hosts: Tailscale IPs (100.64.0.0/10, fd7a:115c:a1e0::/48),
     * MagicDNS names (*.ts.net) and bare MagicDNS short names ("myserver").
     */
    fun isTailnetHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h.endsWith(".ts.net")) return true
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 100 && b in 64..127
        }
        // Single-label names (no dots, not an IPv6 literal) are MagicDNS short names.
        return !h.contains('.') && !h.contains(':') && h != "localhost" && h.isNotEmpty()
    }
}
