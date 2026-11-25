package com.deemwar.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val pad by lazy { (resources.displayMetrics.density * 12).toInt() }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName) || dpm.isProfileOwnerApp(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { setPadding(pad, pad, pad, pad / 2) }
        val refresh = Button(this).apply {
            text = "Refresh from deemwar repo"
            setOnClickListener { Thread { loadRepo() }.start() }
        }
        val updateAll = Button(this).apply {
            text = "Update all"
            setOnClickListener { Thread { updateAll() }.start() }
        }
        val log = TextView(this).apply { setPadding(pad, 0, pad, pad) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(refresh)
                addView(updateAll)
                addView(list)
                addView(log)
            })
        }
        setContentView(root)

        status.text = if (isDeviceOwner())
            "Device owner: YES — silent installs enabled"
        else
            "Device owner: NO — promote via adb:\ndpm set-device-owner com.deemwar.deviceadmin/.AdminReceiver"
        UpdateWorker.schedule(this)
    }

    /** Auto-find: re-scan whenever any app is installed, updated or removed. */
    private val pkgChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Thread { loadRepo() }.start() }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(pkgChanged, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        Thread { loadRepo() }.start()
    }

    override fun onStop() {
        unregisterReceiver(pkgChanged)
        super.onStop()
    }

    @Volatile private var lastEntries: List<RepoEntry> = emptyList()

    private fun installedVersion(pkg: String): Long = try {
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (e: Exception) { 0L }

    private fun updateAll() {
        val todo = lastEntries.filter { val v = installedVersion(it.packageName); v in 1 until it.versionCode }
        if (todo.isEmpty()) { status.post { status.text = "Everything installed is up to date" }; return }
        for (e in todo) install(e)
    }

    override fun onResume() {
        super.onResume()
        InstallLog.listener = { m ->
            status.post { status.text = m }
            if (m.contains("installed OK")) Thread { loadRepo() }.start()
        }
    }

    override fun onPause() {
        InstallLog.listener = null
        super.onPause()
    }

    private fun loadRepo() {
        try {
            val index = Repo.fetchIndex()
            val pm = packageManager
            val entries = index.values
                .filter { it.packageName != packageName } // don't list ourselves; we ARE the installer
                .sortedBy { it.packageName }
            lastEntries = entries
            runOnUiThread {
                list.removeAllViews()
                for (entry in entries) {
                    val installed = try {
                        pm.getPackageInfo(entry.packageName, 0).longVersionCode
                    } catch (e: Exception) { 0L }
                    list.addView(rowFor(entry, installed))
                }
                if (index.isEmpty()) {
                    list.addView(text("No com.deemwar.* apps found in the repo index."))
                }
            }
        } catch (t: Throwable) {
            runOnUiThread {
                list.removeAllViews()
                list.addView(text("Repo error: ${t.message}"))
            }
        }
    }

    private fun rowFor(entry: RepoEntry, installed: Long): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val label = "${entry.packageName}\n" +
            if (installed == 0L) "not installed — available v${entry.versionCode}"
            else if (installed < entry.versionCode) "v$installed -> v${entry.versionCode} update available"
            else "v$installed — up to date"
        row.addView(text(label))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (installed < entry.versionCode) {
            buttons.addView(Button(this).apply {
                text = if (installed == 0L) "Install" else "Update"
                setOnClickListener { Thread { install(entry) }.start() }
            })
        }
        if (installed > 0L) {
            packageManager.getLaunchIntentForPackage(entry.packageName)?.let { launch ->
                buttons.addView(Button(this).apply {
                    text = "Open"
                    setOnClickListener { startActivity(launch) }
                })
            }
            buttons.addView(Button(this).apply {
                text = "Uninstall"
                setOnClickListener {
                    try { Installer.uninstall(this@MainActivity, entry.packageName) }
                    catch (t: Throwable) { status.text = "${entry.packageName}: ERROR ${t.message}" }
                }
            })
        }
        row.addView(buttons)
        return row
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        setTextIsSelectable(false)
        setPadding(0, 0, 0, 0)
        setTypeface(typeface, if (s.contains('\n')) Typeface.NORMAL else Typeface.NORMAL)
    }

    private fun install(entry: RepoEntry) {
        try {
            status.post { status.text = "${entry.packageName}: downloading…" }
            val apk = File(cacheDir, entry.apkName)
            Repo.downloadApk(entry, apk)
            try {
                Installer.install(this, entry, apk) { m -> status.post { status.text = m } }
            } finally {
                apk.delete()
            }
            status.post { status.text = "${entry.packageName}: install committed, waiting for result…" }
        } catch (t: Throwable) {
            status.post { status.text = "${entry.packageName}: ERROR ${t.message}" }
        }
    }
}
