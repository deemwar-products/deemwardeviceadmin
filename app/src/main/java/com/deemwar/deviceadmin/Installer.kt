package com.deemwar.deviceadmin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

object Installer {

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Every signer must be pinned, and there must be at least one. Pure, so it
     * is unit-testable; the certs come from the platform verifier below.
     */
    fun allSignersTrusted(certSha256s: List<String>): Boolean =
        certSha256s.isNotEmpty() && certSha256s.all { Policy.isTrustedCert(it) }

    /**
     * Signing-cert SHA-256s of an APK file, read by the platform's own parser,
     * which verifies v1, v2 and v3 signatures. (The old JarFile reader only saw
     * v1 signatures, so v2-only APKs were rejected as "not signed".)
     */
    fun apkSigners(pm: PackageManager, apk: File): Pair<String?, List<String>> {
        val info = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        info ?: throw IllegalStateException("APK could not be parsed")
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: throw IllegalStateException("APK is not signed")
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else @Suppress("DEPRECATION") info.signatures
        val list = sigs?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
        if (list.isEmpty()) throw IllegalStateException("APK is not signed")
        return info.packageName to list
    }

    /**
     * Only ask for a prompt-free install when we are already the installer of
     * record (or update owner) for the package. Asking for it otherwise is what
     * left first installs / foreign-installed packages stuck.
     */
    fun shouldRequestSilent(ourPackage: String, installerOfRecord: String?, updateOwner: String?, sdk: Int): Boolean {
        if (sdk < 31) return false
        if (updateOwner != null) return updateOwner == ourPackage
        return installerOfRecord == ourPackage
    }

    private fun installerInfo(pm: PackageManager, pkg: String): Pair<String?, String?> = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val info = pm.getInstallSourceInfo(pkg)
            val owner = if (Build.VERSION.SDK_INT >= 34) info.updateOwnerPackageName else null
            info.installingPackageName to owner
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg) to null
        }
    } catch (e: PackageManager.NameNotFoundException) { null to null }

    /**
     * Silent install (no user consent prompt) when this app is Device Owner;
     * otherwise falls back to a normal prompt. Rejects anything whose signing
     * cert is not pinned in [Policy].
     */
    fun install(context: Context, entry: RepoEntry, apk: File, log: (String) -> Unit) {
        val pm = context.packageManager
        val (apkPackage, certs) = apkSigners(pm, apk)
        if (apkPackage != entry.packageName) {
            apk.delete()
            throw SecurityException("BLOCKED: APK is $apkPackage, index says ${entry.packageName}")
        }
        // signingCertificateHistory lists rotated-from certs too; the current one is last.
        val current = if (certs.size > 1 && Build.VERSION.SDK_INT >= 28) listOf(certs.last()) else certs
        if (!allSignersTrusted(current)) {
            apk.delete()
            throw SecurityException(
                "BLOCKED ${entry.packageName}: signing cert ${current.joinToString()} is not a pinned deemwar cert"
            )
        }
        log("cert OK (${entry.packageName}): ${current.joinToString()}")

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val (recordInstaller, updateOwner) = installerInfo(pm, entry.packageName)
        val silent = shouldRequestSilent(context.packageName, recordInstaller, updateOwner, Build.VERSION.SDK_INT)
        log("${entry.packageName}: installer=${recordInstaller ?: "none"} owner=${updateOwner ?: "none"} -> ${if (silent) "silent" else "prompt"}")
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(
                if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            )
        }
        // Claim update ownership (Android 14+): no other installer can override our updates.
        if (Build.VERSION.SDK_INT >= 34) {
            params.setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite(entry.packageName, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(InstallStatusReceiver.ACTION)
                .setClass(context, InstallStatusReceiver::class.java)
            val sender: IntentSender = PendingIntent.getBroadcast(
                context, sessionId, statusIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ).intentSender
            session.commit(sender)
            log("install committed for ${entry.packageName} v${entry.versionCode}")
        } finally {
            session.close()
        }
    }
}
