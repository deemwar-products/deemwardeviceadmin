package com.deemwar.deviceadmin

import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * The ONLY source this installer will ever talk to, and the ONLY signing
 * certificates it will ever install from. These values are baked in on
 * purpose: there is no settings screen, no intent-based install entry
 * point, and no fallback. If it is not from this repo with one of these
 * signing certs, it does not get installed.
 */
object Policy {
    const val REPO_URL = "https://fdroid.deemwar.com/repo"
    const val INDEX_URL = "$REPO_URL/index-v1.json"

    /**
     * Every package the repo serves may be installed — the repo IS the
     * allowlist. The real gate is the pinned signing certs below: no APK is
     * ever installed unless its signing certificate matches.
     */
    const val ALLOWED_PACKAGE_PREFIX = ""

    /** SHA-256 of the APK signing certificate, per signer CN. */
    val TRUSTED_CERT_SHA256 = setOf(
        // CN=UPI Forwarder, O=Deemwar, C=IN  (current repo apps)
        "d16030fd1dae9a090bb2ad4711f64d76bc8be780d624cf9f33d3e09b64c7fd28",
        // CN=Deemwar Device Admin  (this app, for self-updates)
        "c0d7354c6d1b535207a11b2fd81eda9fe637a9bd7905ee2306e73aea0dd5490c",
        // CN=Deemwar Samples, O=Deemwar, C=IN  (hello / counter test apps)
        "6bd08994e88c568bb8c7502d7d477746d36bb4fad4f123eedc12c59bda742ab1",
    )

    fun isAllowedPackage(packageName: String) =
        packageName.startsWith(ALLOWED_PACKAGE_PREFIX)
    fun isTrustedCert(sha256Hex: String) = sha256Hex.lowercase() in TRUSTED_CERT_SHA256
}

data class RepoEntry(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkSha256: String,
)

object Repo {

    @Volatile private var known: List<String> = emptyList()
    fun lastKnownPackages(): List<String> = known

    fun fetchIndex(): Map<String, RepoEntry> {
        Logger.log("refresh: GET ${Policy.INDEX_URL}")
        val conn = URL(Policy.INDEX_URL).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val map = parseIndexV1(body)
            known = map.keys.sorted()
            Logger.log("refresh: index has ${map.size} apps: " +
                map.values.joinToString(", ") { "${it.packageName}=v${it.versionCode}" })
            return map
        } catch (t: Throwable) {
            Logger.log("refresh FAILED: ${t.message}")
            throw t
        } finally {
            conn.disconnect()
        }
    }

    /** Parse the subset of index-v1.json we care about. */
    fun parseIndexV1(body: String): Map<String, RepoEntry> {
        val root = JSONObject(body)
        val out = mutableMapOf<String, RepoEntry>()
        val packages = root.getJSONObject("packages")
        for (pkg in packages.keys()) {
            val versions = packages.getJSONArray(pkg)
            var best: JSONObject? = null
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                val b = best
                if (b == null || v.getLong("versionCode") > b.getLong("versionCode")) best = v
            }
            best ?: continue
            out[pkg] = RepoEntry(
                packageName = pkg,
                versionCode = best.getLong("versionCode"),
                versionName = best.optString("versionName", ""),
                apkName = best.getString("apkName"),
                apkSha256 = normalizeHash(best),
            )
        }
        return out
    }

    /** index-v1 uses hash + hashType (older servers used sha256); accept both. */
    private fun normalizeHash(v: JSONObject): String {
        val type = v.optString("hashType", "sha256").lowercase()
        check(type == "sha256") { "unsupported hashType $type" }
        return v.optString("hash", v.optString("sha256")).lowercase()
    }

    fun downloadApk(entry: RepoEntry, dest: File) {
        val url = Policy.REPO_URL + "/" + entry.apkName
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        Logger.log("download: ${entry.apkName} (${entry.packageName} v${entry.versionCode})")
        try {
            dest.outputStream().use { out -> conn.inputStream.copyTo(out) }
        } finally {
            conn.disconnect()
        }
        // Verify the APK bytes match what the (HTTPS, pinned-host) index says.
        val got = MessageDigest.getInstance("SHA-256").digest(dest.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (got != entry.apkSha256) {
            Logger.log("download FAILED: sha256 mismatch for ${entry.packageName} (got $got want ${entry.apkSha256})")
            throw IllegalStateException("APK sha256 mismatch for ${entry.packageName}")
        }
        Logger.log("download OK: ${entry.apkName} sha256 $got (${dest.length()} bytes)")
    }
}
