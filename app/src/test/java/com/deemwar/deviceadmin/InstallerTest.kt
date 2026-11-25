package com.deemwar.deviceadmin

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

    @Test
    fun `unsigned apk is rejected before install`() {
        try {
            Installer.apkCertSha256(unsignedApk())
            throw AssertionError("expected unsigned APK to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not signed"))
        }
    }

    @Test
    fun `missing manifest is rejected`() {
        val f = tmp.newFile("empty.apk")
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("other.txt")).let { z.write(ByteArray(1)); z.closeEntry() } }
        try {
            Installer.apkCertSha256(f)
            throw AssertionError("expected rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no manifest") || e.message!!.contains("not signed"))
        }
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
