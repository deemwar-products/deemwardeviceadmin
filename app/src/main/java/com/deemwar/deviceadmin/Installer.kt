package com.deemwar.deviceadmin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

object Installer {

    /**
     * Read the SHA-256 of the APK's signing certificate straight out of the
     * file, before it is ever handed to the system installer.
     */
    fun apkCertSha256(apk: File): String {
        JarFile(apk).use { jar ->
            val entries = jar.entries().toList()
            if (entries.none { it.name == "AndroidManifest.xml" })
                throw IllegalStateException("no manifest in APK")
            val je = entries.first { it.name == "AndroidManifest.xml" }
            jar.getInputStream(je).use { _ -> } // force verification of the entry
            val certs = je.certificates
                ?: throw IllegalStateException("APK is not signed")
            val main = certs.maxByOrNull { it.publicKey.encoded.size }
                ?: throw IllegalStateException("no signer")
            return MessageDigest.getInstance("SHA-256").digest(main.encoded)
                .joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Silent install (no user consent prompt) when this app is Device Owner;
     * otherwise falls back to a normal prompt. Rejects anything whose signing
     * cert is not pinned in [Policy].
     */
    fun install(context: Context, entry: RepoEntry, apk: File, log: (String) -> Unit) {
        val cert = apkCertSha256(apk)
        if (!Policy.isTrustedCert(cert)) {
            apk.delete()
            throw SecurityException(
                "BLOCKED ${entry.packageName}: signing cert $cert is not a pinned deemwar cert"
            )
        }
        log("cert OK (${entry.packageName}): $cert")

        val pm = context.packageManager
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Silent update path: we are (or become) the installer of record for the
        // packages we ship, so updates do not need a user prompt (Android 12+).
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
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
            val statusIntent = Intent("com.deemwar.deviceadmin.INSTALL_STATUS")
                .setPackage(context.packageName)
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
