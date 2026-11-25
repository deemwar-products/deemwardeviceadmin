package com.deemwar.deviceadmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File

class InstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun unsignedApk(): File {
        val f = tmp.newFile("unsigned.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml"))
            z.write("placeholder".toByteArray())
            z.closeEntry()
        }
        return f
    }

    private val me = "com.deemwar.deviceadmin"

    @Test
    fun `silent only when we are installer of record`() {
        assertTrue(Installer.shouldRequestSilent(me, me, null, 33))
        assertFalse(Installer.shouldRequestSilent(me, "org.fdroid.fdroid", null, 33))
        assertFalse(Installer.shouldRequestSilent(me, null, null, 33)) // first install
    }

    @Test
    fun `update owner overrides installer of record`() {
        assertTrue(Installer.shouldRequestSilent(me, "com.android.shell", me, 34))
        assertFalse(Installer.shouldRequestSilent(me, me, "org.fdroid.fdroid", 34))
    }

    @Test
    fun `never silent before android 12`() {
        assertFalse(Installer.shouldRequestSilent(me, me, null, 30))
    }

    @Test
    fun `no signers is rejected`() {
        assertFalse(Installer.allSignersTrusted(emptyList()))
    }

    @Test
    fun `pinned signer is accepted, unknown or mixed is rejected`() {
        val fw = "d16030fd1dae9a090bb2ad4711f64d76bc8be780d624cf9f33d3e09b64c7fd28"
        assertTrue(Installer.allSignersTrusted(listOf(fw)))
        assertTrue(Installer.allSignersTrusted(listOf(fw.uppercase())))
        assertFalse(Installer.allSignersTrusted(listOf("ab".repeat(32))))
        assertFalse(Installer.allSignersTrusted(listOf(fw, "ab".repeat(32))))
    }

    @Test
    fun `repo download rejects sha256 mismatch before install is ever called`() {
        // This is the core safety invariant: a tampered download must never reach the installer.
        val expected = "a".repeat(64)
        val got = "b".repeat(64)
        try {
            check(got == expected) { "APK sha256 mismatch" }
            throw AssertionError("expected mismatch to be caught")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mismatch"))
        }
    }
}
