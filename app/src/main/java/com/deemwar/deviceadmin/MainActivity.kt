package com.deemwar.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import rikka.shizuku.Shizuku
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
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
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
            text = "Install / update ALL apps"
            setOnClickListener { Thread { installAll() }.start() }
        }
        val shizukuBtn = Button(this).apply {
            text = "Enable silent install (Shizuku)"
            setOnClickListener { onShizukuButton() }
        }
        Logger.init(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        logView = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            text = Logger.text()
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 220).toInt())
            addView(logView)
        }
        val logButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "Share logs"; setOnClickListener { shareLogs() } })
            addView(Button(this@MainActivity).apply { text = "Copy"; setOnClickListener { copyLogs() } })
            addView(Button(this@MainActivity).apply { text = "Clear"; setOnClickListener { Logger.clear(); logView.text = ""; Logger.log("log cleared") } })
        }

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Deemwar Admin"
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(pad, pad, pad, 0)
                })
                addView(status)
                addView(refresh)
                addView(updateAll)
                addView(shizukuBtn)
                addView(list)
                addView(TextView(this@MainActivity).apply { text = "Activity log"; setPadding(pad, pad, pad, 0); setTypeface(typeface, Typeface.BOLD) })
                addView(logButtons)
                addView(logScroll)
            })
        }
        setContentView(root)
        // targetSdk 35 forces edge-to-edge on Android 15+: pad for the status/nav bars
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        Logger.log("app opened")

        status.text = if (isDeviceOwner())
            "Device owner: YES — silent installs enabled"
        else
            "Device owner: NO — promote via adb:\ndpm set-device-owner com.deemwar.deviceadmin/.AdminReceiver"
        UpdateWorker.schedule(this)

        try {
            Shizuku.addRequestPermissionResultListener { _, result ->
                val ok = result == android.content.pm.PackageManager.PERMISSION_GRANTED
                Logger.log("shizuku permission " + if (ok) "GRANTED — silent install enabled" else "denied")
                runOnUiThread { status.text = if (ok) "Shizuku ready — installs are now silent" else "Shizuku permission denied" }
            }
        } catch (t: Throwable) {}
        refreshShizukuStatus()
    }

    private fun shizukuState(): String = when {
        !ShizukuInstaller.binderAlive() -> "Shizuku not running"
        ShizukuInstaller.hasPermission() -> "Shizuku ready — installs are silent"
        else -> "Shizuku running — tap to grant permission"
    }

    private fun refreshShizukuStatus() { status.post { status.text = shizukuState() } }

    private fun onShizukuButton() {
        when {
            !ShizukuInstaller.binderAlive() -> {
                Logger.log("Shizuku not running. Install the Shizuku app and start it via Wireless debugging (Developer options), then return here.")
                status.text = "Install Shizuku + start it via Wireless debugging, then tap again"
            }
            ShizukuInstaller.hasPermission() -> { status.text = "Shizuku already enabled — installs are silent" }
            else -> { Logger.log("requesting Shizuku permission"); ShizukuInstaller.requestPermission() }
        }
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

    /** Install every repo app that is missing or out of date, in one go. */
    private fun installAll() {
        val todo = lastEntries.filter { installedVersion(it.packageName) < it.versionCode }
        if (todo.isEmpty()) { Logger.log("install all: everything already up to date"); status.post { status.text = "All apps installed and up to date" }; return }
        Logger.log("install all: " + todo.size + " app(s): " + todo.joinToString { it.packageName })
        if (!ShizukuInstaller.hasPermission()) Logger.log("install all: Shizuku not enabled — each app may show a prompt")
        var ok = 0
        for (e in todo) { install(e); if (installedVersion(e.packageName) >= e.versionCode) ok++ }
        Logger.log("install all: " + ok + "/" + todo.size + " installed")
        status.post { status.text = "Install all: " + ok + "/" + todo.size + " done" }
    }

    override fun onResume() {
        super.onResume()
        Logger.listener = { line ->
            runOnUiThread {
                logView.append("\n" + line)
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                if (line.contains(": ") && (line.contains("installed OK") || line.contains("uninstalled OK"))) {
                    status.text = line.substringAfter("  ")
                    Thread { loadRepo() }.start()
                } else if (line.endsWith("confirmation") || line.contains("FAILED") || line.contains("ERROR")) {
                    status.text = line.substringAfter("  ")
                }
            }
        }
    }

    override fun onPause() {
        Logger.listener = null
        super.onPause()
    }

    private fun shareLogs() {
        val body = Logger.diagnostics(this) + Logger.text()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Deemwar Admin logs")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, "Send Deemwar Admin logs"))
    }

    private fun copyLogs() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Deemwar Admin logs", Logger.diagnostics(this) + Logger.text()))
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun loadRepo() {
        try {
            Logger.log("refresh: scanning installed versions")
            val index = Repo.fetchIndex()
            val pm = packageManager
            val entries = index.values
                .filter { it.packageName != packageName && it.packageName != "moe.shizuku.privileged.api" }
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
            Logger.log("refresh ERROR: ${t.message}")
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
                    Logger.log("uninstall: ${entry.packageName} requested")
                    try { Installer.uninstall(this@MainActivity, entry.packageName) }
                    catch (t: Throwable) { Logger.log("uninstall ERROR ${entry.packageName}: ${t.message}") }
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
            Logger.log("install: ${entry.packageName} requested")
            val apk = File(cacheDir, entry.apkName)
            Repo.downloadApk(entry, apk)
            try {
                Installer.install(this, entry, apk) { Logger.log(it) }
            } finally {
                apk.delete()
            }
        } catch (t: Throwable) {
            Logger.log("install ERROR ${entry.packageName}: ${t.message}")
        }
    }
}
