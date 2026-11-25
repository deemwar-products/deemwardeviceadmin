package com.deemwar.deviceadmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {

    @Test
    fun `empty prefix allows every package - repo is the allowlist`() {
        assertEquals("", Policy.ALLOWED_PACKAGE_PREFIX)
        assertTrue(Policy.isAllowedPackage("com.anything.app"))
        assertTrue(Policy.isAllowedPackage("org.fdroid.fdroid"))
    }

    @Test
    fun `prefix filter works when set`() {
        // behaviour contract: startsWith, case-sensitive, exact prefix
        fun allowed(name: String, prefix: String) = name.startsWith(prefix)
        assertTrue(allowed("com.deemwar.upipay.forwarder", "com.deemwar."))
        assertFalse(allowed("com.deemwar", "com.deemwar."))
        assertFalse(allowed("Com.deemwar.app", "com.deemwar."))
        assertFalse(allowed("com.notdeemwar.app", "com.deemwar."))
    }

    @Test
    fun `both pinned certs are trusted`() {
        assertTrue(Policy.isTrustedCert("d16030fd1dae9a090bb2ad4711f64d76bc8be780d624cf9f33d3e09b64c7fd28"))
        assertTrue(Policy.isTrustedCert("c0d7354c6d1b535207a11b2fd81eda9fe637a9bd7905ee2306e73aea0dd5490c"))
    }

    @Test
    fun `cert comparison is case-insensitive`() {
        assertTrue(Policy.isTrustedCert("D16030FD1DAE9A090BB2AD4711F64D76BC8BE780D624CF9F33D3E09B64C7FD28"))
    }

    @Test
    fun `unknown certs are rejected`() {
        assertFalse(Policy.isTrustedCert("0000000000000000000000000000000000000000000000000000000000000000"))
        assertFalse(Policy.isTrustedCert(""))
        assertFalse(Policy.isTrustedCert("deadbeef"))
    }

    @Test
    fun `repo urls are pinned https`() {
        assertTrue(Policy.REPO_URL.startsWith("https://"))
        assertEquals("https://fdroid.deemwar.com/repo", Policy.REPO_URL)
        assertEquals("https://fdroid.deemwar.com/repo/index-v1.json", Policy.INDEX_URL)
    }
}
