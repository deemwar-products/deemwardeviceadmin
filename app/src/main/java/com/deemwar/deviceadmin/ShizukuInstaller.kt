package com.deemwar.deviceadmin

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Installs via Shizuku, i.e. as the ADB shell user (uid 2000). That path is NOT
 * gated by the work-profile restriction DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
 * that blocks the normal PackageInstaller consent flow (proven on the owner's
 * Intune-managed phone, 2026-11-25). Shizuku must be installed and started once
 * per boot (via wireless debugging, no PC); Deemwar then installs silently.
 *
 * The APK is streamed through 'pm install-write ... -' on stdin, so the shell
 * user never needs to read our app-private cache file.
 */
object ShizukuInstaller {

    const val REQUEST_CODE = 4242

    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    fun hasPermission(): Boolean = try {
        binderAlive() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    fun requestPermission() = try { Shizuku.requestPermission(REQUEST_CODE) } catch (t: Throwable) {}

    // Shizuku.newProcess is private in the api artifact; it returns a
    // ShizukuRemoteProcess (extends java.lang.Process). Reach it by reflection.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    private fun exec(cmd: Array<String>): Process =
        newProcess.invoke(null, cmd, null, null) as Process

    /** @return true on a successful commit. Streams the APK via stdin. */
    fun install(apk: File, installerPkg: String, log: (String) -> Unit): Boolean {
        // create
        val create = exec(arrayOf("pm", "install-create", "-r", "-i", installerPkg, "--user", "0"))
        val createOut = create.inputStream.bufferedReader().readText()
        create.waitFor()
        val sid = Regex("\\[(\\d+)\\]").find(createOut)?.groupValues?.get(1)
            ?: Regex("session (\\d+)").find(createOut)?.groupValues?.get(1)
            ?: run { log("shizuku: no session id in: ${createOut.trim()}"); return false }
        log("shizuku: session $sid")
        // write (stream apk bytes to stdin)
        val write = exec(arrayOf("pm", "install-write", "-S", apk.length().toString(), sid, "base", "-"))
        try {
            apk.inputStream().use { input -> write.outputStream.use { out -> input.copyTo(out) } }
        } catch (t: Throwable) {
            log("shizuku: write failed: ${t.message}")
            exec(arrayOf("pm", "install-abandon", sid)).waitFor()
            return false
        }
        val writeOut = write.inputStream.bufferedReader().readText()
        if (write.waitFor() != 0) {
            log("shizuku: install-write failed: ${writeOut.trim()}")
            exec(arrayOf("pm", "install-abandon", sid)).waitFor()
            return false
        }
        // commit
        val commit = exec(arrayOf("pm", "install-commit", sid))
        val commitOut = (commit.inputStream.bufferedReader().readText() + commit.errorStream.bufferedReader().readText())
        val code = commit.waitFor()
        log("shizuku: commit -> ${commitOut.trim().ifEmpty { "exit $code" }}")
        return code == 0 && commitOut.contains("Success")
    }
}
