package com.deemwar.deviceadmin

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One timestamped log for the whole app: refresh, downloads, cert checks,
 * install decisions and PackageInstaller results. Kept in memory (ring buffer)
 * and mirrored to a file so a background-worker run is still there when the UI
 * next opens. [listener] lets the open screen show lines live.
 */
object Logger {
    private const val MAX_LINES = 2000
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    @Volatile var listener: ((String) -> Unit)? = null

    fun init(context: Context) {
        if (file == null) {
            file = File(context.applicationContext.filesDir, "deemwar-admin.log")
            synchronized(lines) {
                if (lines.isEmpty()) file?.takeIf { it.exists() }?.readLines()?.takeLast(MAX_LINES)?.let { lines.addAll(it) }
            }
        }
    }

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try { file?.appendText(line + "\n") } catch (_: Throwable) {}
        listener?.invoke(line)
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Throwable) {}
    }

    /** A header for the shared report: device, Android version, owner status. */
    fun diagnostics(context: Context): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        sb.append("Deemwar Admin diagnostics\n")
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        try {
            val me = pm.getPackageInfo(context.packageName, 0)
            sb.append("admin app: v${me.versionName} (${me.longVersionCode})\n")
        } catch (_: Throwable) {}
        sb.append("--- installed repo apps ---\n")
        for (cert in Policy.TRUSTED_CERT_SHA256) { /* nothing */ }
        sb.append(installedReport(context))
        sb.append("--- log ---\n")
        return sb.toString()
    }

    private fun installedReport(context: Context): String {
        val pm = context.packageManager
        val sb = StringBuilder()
        for (pkg in Repo.lastKnownPackages()) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val src = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg) else null
                val installer = src?.installingPackageName
                    ?: @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
                val owner = if (Build.VERSION.SDK_INT >= 34) src?.updateOwnerPackageName else null
                sb.append("$pkg v${info.versionName} installer=${installer ?: "none"} owner=${owner ?: "none"}\n")
            } catch (e: Exception) {
                sb.append("$pkg not installed\n")
            }
        }
        return sb.toString()
    }
}

/** Back-compat shim: install-status callbacks log through the shared Logger. */
object InstallLog {
    @Volatile var last: String? = null
    fun post(m: String) { last = m; Logger.log(m) }
}
