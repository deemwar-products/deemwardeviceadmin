package com.deemwar.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val sb = StringBuilder()

    private fun log(msg: String) = runOnUiThread {
        sb.append(msg).append('\n')
        logView.text = sb
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName) || dpm.isProfileOwnerApp(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val status = TextView(this).apply { setPadding(pad, pad, pad, pad / 2) }
        logView = TextView(this).apply { setPadding(pad, 0, pad, pad) }
        val scroll = ScrollView(this).apply { addView(logView) }
        val sync = Button(this).apply {
            text = "Sync + update from deemwar repo"
            setOnClickListener { Thread { sync() }.start() }
        }
        val root = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
            addView(status)
            addView(sync)
            addView(scroll)
        }
        setContentView(root)

        val owner = isDeviceOwner()
        status.text = if (owner)
            "Device owner: YES — silent installs enabled"
        else
            "Device owner: NO — run via adb:\ndpm set-device-owner com.deemwar.deviceadmin/.AdminReceiver"
    }

    private fun sync() {
        try {
            log("index: ${Policy.INDEX_URL}")
            val index = Repo.fetchIndex()
            log("allowlisted packages in repo: ${index.keys.joinToString()}")
            val pm = packageManager
            for ((pkg, entry) in index) {
                val installed = try {
                    pm.getPackageInfo(pkg, 0).longVersionCode
                } catch (e: Exception) { 0L }
                if (installed >= entry.versionCode) {
                    log("$pkg: up to date (v$installed)")
                    continue
                }
                log("$pkg: v$installed -> v${entry.versionCode}, downloading…")
                val apk = File(cacheDir, entry.apkName)
                Repo.downloadApk(entry, apk)
                try {
                    Installer.install(this, entry, apk, ::log)
                } finally {
                    apk.delete()
                }
            }
            log("sync done")
        } catch (t: Throwable) {
            log("ERROR: ${t.message}")
        }
    }
}
