package com.phondrive.webdavspike

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

class WebDavServerTest {

    private lateinit var tempDir: File
    private lateinit var server: WebDavServer

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "webdav-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        server = WebDavServer(tempDir)
    }

    @Test
    fun `auth check - valid credentials`() {
        val auth = "Basic " + Base64.getEncoder().encodeToString("user:pass".toByteArray())
        val method = WebDavServer::class.java.getDeclaredMethod("checkAuth", String::class.java)
        method.isAccessible = true
        assertTrue(method.invoke(server, auth) as Boolean)
    }

    @Test
    fun `auth check - invalid credentials`() {
        val auth = "Basic " + Base64.getEncoder().encodeToString("wrong:creds".toByteArray())
        val method = WebDavServer::class.java.getDeclaredMethod("checkAuth", String::class.java)
        method.isAccessible = false
        // Use reflection to test private method
        val declaredMethod = WebDavServer::class.java.getDeclaredMethod("checkAuth", String::class.java)
        declaredMethod.isAccessible = true
        assertFalse(declaredMethod.invoke(server, auth) as Boolean)
    }

    @Test
    fun `auth check - missing Basic prefix`() {
        val method = WebDavServer::class.java.getDeclaredMethod("checkAuth", String::class.java)
        method.isAccessible = true
        assertFalse(method.invoke(server, "Bearer token") as Boolean)
    }

    @Test
    fun `path decode - normal path`() {
        val method = WebDavServer::class.java.getDeclaredMethod("decodePath", String::class.java)
        method.isAccessible = true
        assertEquals("/Documents", method.invoke(server, "/Documents") as String)
    }

    @Test
    fun `path decode - double slashes`() {
        val method = WebDavServer::class.java.getDeclaredMethod("decodePath", String::class.java)
        method.isAccessible = true
        assertEquals("/path/to/file", method.invoke(server, "//path///to//file") as String)
    }

    @Test
    fun `path decode - empty path`() {
        val method = WebDavServer::class.java.getDeclaredMethod("decodePath", String::class.java)
        method.isAccessible = true
        assertEquals("/", method.invoke(server, "") as String)
    }

    @Test
    fun `path decode - encoded characters`() {
        val method = WebDavServer::class.java.getDeclaredMethod("decodePath", String::class.java)
        method.isAccessible = true
        assertEquals("/path/file name", method.invoke(server, "/path/file%20name") as String)
    }

    @Test
    fun `content type - common extensions`() {
        val method = WebDavServer::class.java.getDeclaredMethod("getContentType", File::class.java)
        method.isAccessible = true

        assertEquals("text/html", method.invoke(server, File("test.html")) as String)
        assertEquals("application/json", method.invoke(server, File("test.json")) as String)
        assertEquals("image/jpeg", method.invoke(server, File("test.jpg")) as String)
        assertEquals("video/mp4", method.invoke(server, File("test.mp4")) as String)
        assertEquals("application/pdf", method.invoke(server, File("test.pdf")) as String)
        assertEquals("text/plain", method.invoke(server, File("test.txt")) as String)
    }

    @Test
    fun `content type - unknown extension`() {
        val method = WebDavServer::class.java.getDeclaredMethod("getContentType", File::class.java)
        method.isAccessible = true
        assertEquals("application/octet-stream", method.invoke(server, File("test.xyz")) as String)
    }

    @Test
    fun `root directory exists`() {
        assertTrue(tempDir.exists())
        assertTrue(tempDir.isDirectory)
    }
}
