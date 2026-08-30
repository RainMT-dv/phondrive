package com.phondrive.webdavspike

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.utils.io.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WebDavServer(private val rootDir: File) {

    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    private val user = "user"
    private val pass = "pass"

    private val METHOD_PROPFIND = HttpMethod("PROPFIND")
    private val METHOD_MKCOL = HttpMethod("MKCOL")
    private val METHOD_MOVE = HttpMethod("MOVE")
    private val METHOD_COPY = HttpMethod("COPY")

    fun install(routing: Routing) {
        routing.route("/{path...}") {
            handle { processRequest(call) }
        }
        routing.route("/") {
            handle { processRequest(call) }
        }
    }

    private suspend fun processRequest(call: ApplicationCall) {
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !checkAuth(authHeader)) {
            call.response.header("WWW-Authenticate", "Basic realm=\"PhonDrive\"")
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val path = decodePath(call.request.path())
        val file = File(rootDir, path).canonicalFile

        if (!file.path.startsWith(rootDir.path)) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        when (call.request.httpMethod) {
            HttpMethod.Options -> {
                call.response.header("DAV", "1, 2")
                call.response.header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, PROPFIND")
                call.respond(HttpStatusCode.OK)
            }
            METHOD_PROPFIND -> handlePropfind(call, file, path)
            HttpMethod.Get -> handleGet(call, file)
            HttpMethod.Head -> handleHead(call, file)
            HttpMethod.Put -> handlePut(call, file)
            HttpMethod.Delete -> handleDelete(call, file)
            METHOD_MKCOL -> handleMkcol(call, file)
            METHOD_MOVE -> handleMove(call, file)
            METHOD_COPY -> handleCopy(call, file)
            else -> call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun checkAuth(header: String): Boolean {
        if (!header.startsWith("Basic ")) return false
        return try {
            val decoded = String(Base64.getDecoder().decode(header.substringAfter("Basic ")))
            val (u, p) = decoded.split(":", limit = 2)
            u == user && p == pass
        } catch (_: Exception) {
            false
        }
    }

    private fun decodePath(path: String): String {
        return java.net.URLDecoder.decode(path, "UTF-8")
            .replace(Regex("//+"), "/")
            .trimEnd('/')
            .ifEmpty { "/" }
    }

    // ── PROPFIND ──────────────────────────────────────────────────────

    private suspend fun handlePropfind(call: ApplicationCall, file: File, path: String) {
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val depth = call.request.headers["Depth"] ?: "1"
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<D:multistatus xmlns:D=\"DAV:\">")

            if (file.isDirectory) {
                appendPropResponse(this, file, path, isCollection = true)
                if (depth == "1") {
                    val children = file.listFiles() ?: emptyArray()
                    for (child in children.sortedBy { it.name }) {
                        val childPath = if (path == "/") "/${child.name}" else "$path/${child.name}"
                        appendPropResponse(this, child, childPath, child.isDirectory)
                    }
                }
            } else {
                appendPropResponse(this, file, path, isCollection = false)
            }

            append("</D:multistatus>")
        }

        call.response.header("Content-Type", "application/xml; charset=utf-8")
        call.respond(HttpStatusCode.MultiStatus, xml)
    }

    private fun appendPropResponse(sb: StringBuilder, file: File, href: String, isCollection: Boolean) {
        val escapedHref = href.replace("&", "&amp;").replace("<", "&lt;")
        val lastModified = dateFormat.format(Date(file.lastModified()))
        val contentLength = if (isCollection) 0 else file.length()

        sb.append("<D:response>")
        sb.append("<D:href>$escapedHref</D:href>")
        sb.append("<D:propstat>")
        sb.append("<D:prop>")
        sb.append("<D:getlastmodified>$lastModified</D:getlastmodified>")
        if (!isCollection) {
            sb.append("<D:getcontentlength>$contentLength</D:getcontentlength>")
            val contentType = getContentType(file)
            sb.append("<D:getcontenttype>$contentType</D:getcontenttype>")
        }
        if (isCollection) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
        } else {
            sb.append("<D:resourcetype></D:resourcetype>")
        }
        sb.append("</D:prop>")
        sb.append("<D:status>HTTP/1.1 200 OK</D:status>")
        sb.append("</D:propstat>")
        sb.append("</D:response>")
    }

    // ── GET ───────────────────────────────────────────────────────────

    private suspend fun handleGet(call: ApplicationCall, file: File) {
        if (!file.exists() || file.isDirectory) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        call.response.header("Content-Type", getContentType(file))
        call.response.header("Content-Length", file.length().toString())
        call.response.header("Last-Modified", dateFormat.format(Date(file.lastModified())))
        call.respondFile(file)
    }

    // ── HEAD ──────────────────────────────────────────────────────────

    private suspend fun handleHead(call: ApplicationCall, file: File) {
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        call.response.header("Content-Type", getContentType(file))
        call.response.header("Content-Length", file.length().toString())
        call.response.header("Last-Modified", dateFormat.format(Date(file.lastModified())))
        call.respond(HttpStatusCode.OK)
    }

    // ── PUT ───────────────────────────────────────────────────────────

    private suspend fun handlePut(call: ApplicationCall, file: File) {
        try {
            file.parentFile?.mkdirs()
            val bytes = call.receiveChannel().toByteArray()
            file.writeBytes(bytes)
            if (file.exists()) {
                call.respond(HttpStatusCode.Created)
            } else {
                call.respond(HttpStatusCode.OK)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Unknown error")
        }
    }

    // ── DELETE ─────────────────────────────────────────────────────────

    private suspend fun handleDelete(call: ApplicationCall, file: File) {
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val deleted = if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }

        if (deleted) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.InternalServerError, "Failed to delete")
        }
    }

    // ── MKCOL ─────────────────────────────────────────────────────────

    private suspend fun handleMkcol(call: ApplicationCall, file: File) {
        if (file.exists()) {
            call.respond(HttpStatusCode.MethodNotAllowed)
            return
        }

        if (file.mkdirs()) {
            call.respond(HttpStatusCode.Created)
        } else {
            call.respond(HttpStatusCode.InternalServerError, "Failed to create directory")
        }
    }

    // ── MOVE ──────────────────────────────────────────────────────────

    private suspend fun handleMove(call: ApplicationCall, file: File) {
        val destination = call.request.headers["Destination"]
        if (destination == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing Destination header")
            return
        }

        val destPath = decodePath(destination)
        val destFile = File(rootDir, destPath).canonicalFile

        if (!destFile.path.startsWith(rootDir.path)) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        destFile.parentFile?.mkdirs()
        val success = file.renameTo(destFile)
        if (success) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.InternalServerError, "Failed to move")
        }
    }

    // ── COPY ──────────────────────────────────────────────────────────

    private suspend fun handleCopy(call: ApplicationCall, file: File) {
        val destination = call.request.headers["Destination"]
        if (destination == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing Destination header")
            return
        }

        val destPath = decodePath(destination)
        val destFile = File(rootDir, destPath).canonicalFile

        if (!destFile.path.startsWith(rootDir.path)) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        try {
            if (file.isDirectory) {
                file.copyRecursively(destFile, overwrite = true)
            } else {
                destFile.parentFile?.mkdirs()
                file.copyTo(destFile, overwrite = true)
            }
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Copy failed")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun getContentType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "txt", "log", "md" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
