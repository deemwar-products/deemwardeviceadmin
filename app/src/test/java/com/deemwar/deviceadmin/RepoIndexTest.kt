package com.deemwar.deviceadmin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoIndexTest {

    private fun index(vararg pkgs: Pair<String, String>): String {
        // builds an index-v1 style body: packages -> { pkg: [ {version...} ] }
        val packages = JSONObject()
        for ((pkg, hash) in pkgs) {
            packages.put(pkg, JSONArray()
                .put(JSONObject()
                    .put("versionCode", 2L)
                    .put("versionName", "1.1")
                    .put("apkName", "${pkg}_2.apk")
                    .put("hash", hash)
                    .put("hashType", "sha256")))
            packages.put(pkg, JSONArray()
                .put(packages.getJSONArray(pkg).get(0))
                .put(JSONObject()
                    .put("versionCode", 1L)
                    .put("versionName", "1.0")
                    .put("apkName", "${pkg}_1.apk")
                    .put("hash", "old$hash")
                    .put("hashType", "sha256")))
        }
        return JSONObject().put("packages", packages).toString()
    }

    @Test
    fun `parses hash + hashType from index-v1`() {
        val out = Repo.parseIndexV1(index("com.a.app" to "abc123"))
        assertEquals("abc123", out["com.a.app"]!!.apkSha256)
        assertEquals("com.a.app_2.apk", out["com.a.app"]!!.apkName)
        assertEquals(2L, out["com.a.app"]!!.versionCode)
    }

    @Test
    fun `falls back to legacy sha256 field`() {
        val body = JSONObject().put("packages", JSONObject()
            .put("com.a.app", JSONArray().put(JSONObject()
                .put("versionCode", 1L)
                .put("apkName", "x.apk")
                .put("sha256", "legacyhash"))))
        assertEquals("legacyhash", Repo.parseIndexV1(body.toString())["com.a.app"]!!.apkSha256)
    }

    @Test
    fun `rejects non-sha256 hash types`() {
        val body = JSONObject().put("packages", JSONObject()
            .put("com.a.app", JSONArray().put(JSONObject()
                .put("versionCode", 1L)
                .put("apkName", "x.apk")
                .put("hash", "h")
                .put("hashType", "md5"))))
        try {
            Repo.parseIndexV1(body.toString())
            assertFalse("expected rejection", true)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("hashType"))
        }
    }

    @Test
    fun `picks highest versionCode among entries`() {
        val out = Repo.parseIndexV1(index("com.a.app" to "abc123"))
        assertEquals(2L, out["com.a.app"]!!.versionCode)
        assertEquals("1.1", out["com.a.app"]!!.versionName)
    }

    @Test
    fun `parses every package the repo serves`() {
        val out = Repo.parseIndexV1(index(
            "com.a.app" to "h1",
            "org.b.app" to "h2",
            "net.c.app" to "h3"))
        assertEquals(3, out.size)
    }

    @Test
    fun `empty index yields empty map`() {
        assertTrue(Repo.parseIndexV1("""{"packages":{}}""").isEmpty())
    }

    @Test
    fun `hash comparison is lowercase on both sides`() {
        val out = Repo.parseIndexV1(index("com.a.app" to "ABC123"))
        assertEquals("abc123", out["com.a.app"]!!.apkSha256)
    }
}
