package com.ethic.vpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Builds the Xray-core JSON that libXray runs inside the VPN service. */
object XrayConfig {
    const val TUN_MTU = 1500
    /** What the system resolver is pointed at; Xray hijacks these queries. */
    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
    /** Upstreams Xray resolves through the proxy. DoH rides a TCP stream, so DNS does not depend on UDP via the server. */
    val UPSTREAM_DNS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
    )

    /**
     * Russian sites that go past the VPN when split tunneling is on: the category-ru list
     * from geodata/geosite.dat and the .ru, .su and .рф zones.
     */
    val RUSSIAN_DOMAINS = listOf("geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai")
    /** Resolver for those sites, reached directly (its address is in geoip:ru), so they get the addresses a Russian network would. */
    const val RUSSIAN_DNS = "77.88.8.8"
    /**
     * Upstream DNS picked in settings. AdGuard is plain DNS over TCP to its IP, which needs no
     * certificate for the address; it also filters ads.
     */
    fun upstreamDns(choice: String, custom: String = ""): List<String> = when (choice) {
        "cloudflare" -> listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")
        "google" -> listOf("https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query")
        "adguard" -> listOf("tcp://94.140.14.14:53", "tcp://94.140.15.15:53")
        "custom" -> customDns(custom)?.let { listOf(it) } ?: UPSTREAM_DNS
        else -> UPSTREAM_DNS
    }

    /**
     * What the user typed as their own DNS: an IPv4 address becomes DNS over TCP to it, an
     * https:// URL is used as DoH. Anything else is null.
     */
    fun customDns(entry: String): String? {
        val e = entry.trim()
        if (e.startsWith("https://")) return e.takeIf { it.length > 8 && ' ' !in it }
        val parts = e.split('.')
        val isIp = parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all(Char::isDigit) && p.toInt() <= 255 }
        return if (isIp) "tcp://$e:53" else null
    }

    /** Ad and tracker domains (geodata/geosite.dat) dropped when ad blocking is on. */
    val AD_DOMAINS = listOf("geosite:category-ads-all")

    /**
     * @param tunFd the VpnService tun descriptor; Xray reads it from the `xray.tun.fd` env key.
     *              Pass null to build a config for validation only.
     * @param logPath file for Xray's error log at info level, used for troubleshooting.
     * @param russiaDirect send Russian sites and IP ranges past the VPN.
     * @param assetDir directory with geoip.dat and geosite.dat, needed for [russiaDirect] and [blockAds].
     * @param directSites the user's sites that always go past the VPN.
     * @param proxySites the user's sites that always go through the VPN, even Russian ones.
     * @param blockAds drop connections to ad and tracker domains.
     * @param dns upstream resolvers, reached through the proxy; see [upstreamDns].
     * @param accessLogPath file for Xray's access log, one line per connection; blocked ones end
     *                      in "-> block]", which is how blocked ads are counted ([countBlocked]).
     */
    fun build(
        profile: ServerProfile,
        tunFd: Int?,
        logPath: String? = null,
        russiaDirect: Boolean = false,
        assetDir: String? = null,
        directSites: List<String> = emptyList(),
        proxySites: List<String> = emptyList(),
        blockAds: Boolean = false,
        dns: List<String> = UPSTREAM_DNS,
        accessLogPath: String? = null,
    ): String {
        val root = JSONObject()
        root.put(
            "log",
            (
                if (logPath == null) JSONObject().put("loglevel", "warning")
                else JSONObject().put("loglevel", "info").put("access", "none").put("error", logPath)
                ).apply { if (accessLogPath != null) put("access", accessLogPath) },
        )
        val env = JSONObject()
        if (tunFd != null) env.put("xray.tun.fd", tunFd.toString())
        if ((russiaDirect || blockAds) && assetDir != null) env.put("xray.location.asset", assetDir)
        if (env.length() > 0) root.put("env", env)

        val dnsServers = JSONArray()
        if (russiaDirect) {
            dnsServers.put(
                JSONObject()
                    .put("address", RUSSIAN_DNS)
                    .put("domains", JSONArray(RUSSIAN_DOMAINS))
                    .put("skipFallback", true),
            )
        }
        dns.forEach { dnsServers.put(it) }
        root.put("dns", JSONObject().put("servers", dnsServers).put("queryStrategy", "UseIPv4"))
        val inbounds = JSONArray().put(
                JSONObject()
                    .put("tag", "tun-in")
                    .put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "xray0").put("mtu", TUN_MTU))
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                            .put("routeOnly", true),
                    ),
            )
        root.put("inbounds", inbounds)
        root.put(
            "outbounds",
            JSONArray()
                .put(outbound(profile))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                .put(JSONObject().put("tag", "dns-out").put("protocol", "dns")),
        )
        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("inboundTag", JSONArray(listOf("tun-in")))
                    .put("port", "53")
                    .put("outboundTag", "dns-out"),
            )
            .put(JSONObject().put("ip", JSONArray(PRIVATE_CIDRS)).put("outboundTag", "direct"))
        // The user's own lists come first, so they win over ad blocking and the Russian rules.
        siteRules(proxySites).takeIf { it.isNotEmpty() }?.let {
            rules.put(JSONObject().put("domain", JSONArray(it)).put("outboundTag", "proxy"))
        }
        siteRules(directSites).takeIf { it.isNotEmpty() }?.let {
            rules.put(JSONObject().put("domain", JSONArray(it)).put("outboundTag", "direct"))
        }
        if (blockAds) rules.put(JSONObject().put("domain", JSONArray(AD_DOMAINS)).put("outboundTag", "block"))
        if (russiaDirect) {
            rules.put(JSONObject().put("domain", JSONArray(RUSSIAN_DOMAINS)).put("outboundTag", "direct"))
            rules.put(JSONObject().put("ip", JSONArray(listOf("geoip:ru"))).put("outboundTag", "direct"))
        }
        root.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
        return root.toString()
    }

    /**
     * The Xray config for a router (XKeen on Keenetic): its outbounds and routing, to drop into
     * 04_outbounds.json and 05_routing.json. The router keeps its own inbounds and DNS.
     */
    fun routerOutbounds(profile: ServerProfile): String = JSONObject().put(
        "outbounds",
        JSONArray()
            .put(outbound(profile))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole")),
    ).toString(2)

    fun routerRouting(
        russiaDirect: Boolean,
        blockAds: Boolean = false,
        directSites: List<String> = emptyList(),
        proxySites: List<String> = emptyList(),
    ): String {
        val rules = JSONArray().put(JSONObject().put("type", "field").put("ip", JSONArray(PRIVATE_CIDRS)).put("outboundTag", "direct"))
        siteRules(proxySites).takeIf { it.isNotEmpty() }?.let { rules.put(JSONObject().put("type", "field").put("domain", JSONArray(it)).put("outboundTag", "proxy")) }
        siteRules(directSites).takeIf { it.isNotEmpty() }?.let { rules.put(JSONObject().put("type", "field").put("domain", JSONArray(it)).put("outboundTag", "direct")) }
        if (blockAds) rules.put(JSONObject().put("type", "field").put("domain", JSONArray(AD_DOMAINS)).put("outboundTag", "block"))
        if (russiaDirect) {
            rules.put(JSONObject().put("type", "field").put("domain", JSONArray(RUSSIAN_DOMAINS)).put("outboundTag", "direct"))
            rules.put(JSONObject().put("type", "field").put("ip", JSONArray(listOf("geoip:ru"))).put("outboundTag", "direct"))
        }
        // Everything else through the VPN.
        rules.put(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "proxy"))
        return JSONObject().put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", rules)).toString(2)
    }

    /** Connections Xray sent to the blackhole in a piece of its access log. */
    fun countBlocked(accessLog: String): Int = Regex("""-> block]""").findAll(accessLog).count()

    /**
     * Xray domain rules for what a user typed: "https://www.Site.com/page", "*.site.com" and
     * "site.com" all become "domain:site.com", which also covers subdomains. Junk is dropped.
     */
    fun siteRules(entries: List<String>): List<String> = entries.mapNotNull { siteDomain(it) }.distinct().map { "domain:$it" }

    fun siteDomain(entry: String): String? {
        val host = entry.trim().lowercase()
            .substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            .removePrefix("*.").removePrefix("www.").trim('.')
        return host.takeIf { Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""").matches(it) || Regex("""^[\p{L}0-9-]+(\.[\p{L}0-9-]+)+$""").matches(it) }
    }

    fun outbound(p: ServerProfile): JSONObject = when (p.protocol) {
        ServerProfile.TROJAN -> JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("address", p.address).put("port", p.port).put("password", p.password))
            .put("streamSettings", streamSettings(p))
        else -> {
            val settings = JSONObject()
                .put("address", p.address)
                .put("port", p.port)
                .put("id", p.uuid)
                .put("encryption", p.encryption)
            if (p.flow.isNotEmpty()) settings.put("flow", p.flow)
            JSONObject()
                .put("tag", "proxy")
                .put("protocol", "vless")
                .put("settings", settings)
                .put("streamSettings", streamSettings(p))
        }
    }

    private fun streamSettings(p: ServerProfile): JSONObject {
        val s = JSONObject().put("network", p.network).put("security", p.security)
        val serverName = p.sni.ifEmpty { p.host }

        when (p.security) {
            "reality" -> s.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", serverName)
                    .put("fingerprint", p.fingerprint.ifEmpty { "chrome" })
                    .put("password", p.publicKey)
                    .put("shortId", p.shortId)
                    .putIfNotEmpty("spiderX", p.spiderX)
                    .putIfNotEmpty("mldsa65Verify", p.mldsa65Verify),
            )
            "tls" -> s.put(
                "tlsSettings",
                JSONObject()
                    .putIfNotEmpty("serverName", serverName)
                    .putIfNotEmpty("fingerprint", p.fingerprint)
                    .apply { if (p.alpn.isNotEmpty()) put("alpn", JSONArray(p.alpn)) }
                    .putIfNotEmpty("pinnedPeerCertSha256", p.pinnedPeerCertSha256)
                    .putIfNotEmpty("verifyPeerCertByName", p.verifyPeerCertByName)
                    .putIfNotEmpty("echConfigList", p.echConfigList),
            )
        }

        when (p.network) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", p.path.ifEmpty { "/" }).putIfNotEmpty("host", p.host))
            "httpupgrade" -> s.put(
                "httpupgradeSettings",
                JSONObject().put("path", p.path.ifEmpty { "/" }).putIfNotEmpty("host", p.host),
            )
            "grpc" -> s.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", p.serviceName)
                    .putIfNotEmpty("authority", p.authority)
                    .put("multiMode", p.mode == "multi"),
            )
            "xhttp" -> {
                val x = JSONObject()
                    .put("path", p.path.ifEmpty { "/" })
                    .putIfNotEmpty("host", p.host)
                    .putIfNotEmpty("mode", p.mode)
                if (p.extra.isNotEmpty()) {
                    runCatching { JSONObject(p.extra) }.getOrNull()?.let { extra ->
                        extra.keys().forEach { k -> x.put(k, extra.get(k)) }
                    }
                }
                s.put("xhttpSettings", x)
            }
            "tcp" -> if (p.headerType == "http") {
                val request = JSONObject().put("path", JSONArray(listOf(p.path.ifEmpty { "/" })))
                if (p.host.isNotEmpty()) {
                    request.put("headers", JSONObject().put("Host", JSONArray(p.host.split(',').map { it.trim() })))
                }
                s.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", request)))
            }
        }
        return s
    }

    private fun JSONObject.putIfNotEmpty(key: String, value: String): JSONObject =
        if (value.isEmpty()) this else put(key, value)
}
