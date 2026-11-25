package com.deemwar.deviceadmin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives PackageInstaller session results. Without this, a session that
 * needs user confirmation (STATUS_PENDING_USER_ACTION) is never shown and the
 * tap appears to do nothing; failures are invisible too.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val pkg = intent.getStringExtra(EXTRA_PKG)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
        val op = intent.getStringExtra(EXTRA_OP) ?: "install"
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                    InstallLog.post("$pkg: waiting for your confirmation")
                } catch (t: Throwable) {
                    InstallLog.post("$pkg: could not open confirm dialog: ${t.message}")
                }
            } else {
                InstallLog.post("$pkg: user action required but no confirm intent")
            }
            return
        }
        InstallLog.post("$pkg: ${if (op == "uninstall" && status == PackageInstaller.STATUS_SUCCESS) "uninstalled OK" else describeStatus(status)}" + (msg?.let { " ($it)" } ?: ""))
    }

    companion object {
        const val ACTION = "com.deemwar.deviceadmin.INSTALL_STATUS"
        const val EXTRA_PKG = "com.deemwar.deviceadmin.PKG"
        const val EXTRA_OP = "com.deemwar.deviceadmin.OP"

        fun describeStatus(status: Int): String = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "installed OK"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "needs confirmation"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILED: aborted/cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILED: blocked by policy"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILED: conflict (signature mismatch?)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILED: incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "FAILED: invalid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILED: storage"
            PackageInstaller.STATUS_FAILURE -> "FAILED"
            else -> "FAILED: status $status"
        }
    }
}

/** Last install result, pushed to whoever is listening (MainActivity). */
object InstallLog {
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var last: String? = null
    fun post(m: String) { last = m; listener?.invoke(m) }
}
