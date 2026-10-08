package dev.sunshinemobile

import android.util.Xml
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GameStream control-plane HTTP scaffold.
 *
 * Exposes only non-sensitive server metadata. Pairing and launch deliberately
 * return explicit errors until a cryptographically authenticated implementation
 * exists. Never treat this alone as a Moonlight-compatible host.
 */
class GameStreamHttpServer(
    private val deviceName: String,
    private val uniqueId: String = UUID.randomUUID().toString()
) {
    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private val active = AtomicBoolean(false)

    @Synchronized fun start(port: Int = 47989) {
        check(!active.get()) { "Server already running" }
        val socket = ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"))
        server = socket
        active.set(true)
        worker = Thread({
            while (active.get()) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                try { serve(client) } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
            }
        }, "gamestream-http").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val first = reader.readLine() ?: return
            val parts = first.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                respond(s, 405, "Method Not Allowed", errorXml(405, "Only GET supported"))
                return
            }
            var line: String?
            do { line = reader.readLine() } while (line != null && line.isNotEmpty())
            val path = parts[1].substringBefore('?')
            when (path) {
                "/serverinfo" -> respond(s, 200, "OK", serverInfo())
                "/applist", "/launch", "/resume", "/cancel", "/pair" ->
                    respond(s, 501, "Not Implemented", errorXml(501, "GameStream authentication/session not implemented"))
                else -> respond(s, 404, "Not Found", errorXml(404, "Unknown endpoint"))
            }
        }
    }

    private fun serverInfo(): String {
        val safeName = xmlEscape(deviceName)
        val safeId = xmlEscape(uniqueId)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root status_code=\"200\" status_message=\"OK\">" +
            "<hostname>$safeName</hostname><uniqueid>$safeId</uniqueid>" +
            "<PairStatus>0</PairStatus><currentgame>0</currentgame>" +
            "<state>SUNSHINE_MOBILE_INCOMPLETE</state>" +
            "</root>"
    }

    private fun errorXml(code: Int, message: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"$code\" status_message=\"${xmlEscape(message)}\"/>"

    private fun xmlEscape(value: String) = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun respond(socket: Socket, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    @Synchronized fun stop() {
        active.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        worker = null
    }
}
