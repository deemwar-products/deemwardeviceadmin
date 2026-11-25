package com.deemwar.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
        val log = TextView(this).apply { setPadding(pad, 0, pad, pad) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(refresh)
                addView(list)
                addView(log)
            })
        }
        setContentView(root)

        status.text = if (isDeviceOwner())
            "Device owner: YES — silent installs enabled"
        else
            "Device owner: NO — promote via adb:\ndpm set-device-owner com.deemwar.deviceadmin/.AdminReceiver"
        Thread { loadRepo() }.start()
    }

    private fun loadRepo() {
        try {
            val index = Repo.fetchIndex()
            val pm = packageManager
            val entries = index.values
                .filter { it.packageName != packageName } // don't list ourselves; we ARE the installer
                .sortedBy { it.packageName }
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
        if (installed < entry.versionCode) {
            row.addView(Button(this).apply {
                text = if (installed == 0L) "Install" else "Update"
                setOnClickListener { Thread { install(entry) }.start() }
            })
        }
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
            status.post { status.text = "${entry.packageName}: install committed" }
            loadRepo()
        } catch (t: Throwable) {
            status.post { status.text = "${entry.packageName}: ERROR ${t.message}" }
        }
    }
}
