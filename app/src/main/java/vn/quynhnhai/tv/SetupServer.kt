package vn.quynhnhai.tv

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Địa chỉ IP của tivi trong mạng Wi-Fi/LAN (null nếu không có mạng). */
fun localIp(): String? {
    try {
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in ifs) {
            if (!nif.isUp || nif.isLoopback) continue
            for (a in nif.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return a.hostAddress
                }
            }
        }
    } catch (e: Exception) {
    }
    return null
}

/**
 * Máy chủ nhỏ chạy tạm trên tivi: điện thoại quét QR, mở trang này, dán link playlist rồi gửi về.
 * Chỉ nhận yêu cầu có đúng mã `k` (nằm trong mã QR).
 */
class SetupServer(
    private val token: String,
    private val current: () -> String,
    private val onUrl: (String) -> Unit
) {
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    val port: Int get() = server?.localPort ?: 0

    fun start(): Boolean {
        return try {
            val s = ServerSocket(0)
            server = s
            running = true
            thread(isDaemon = true) {
                while (running) {
                    try {
                        val c = s.accept()
                        thread(isDaemon = true) { handle(c) }
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
    }

    private fun parse(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (p in s.split("&")) {
            if (p.isEmpty()) continue
            val i = p.indexOf('=')
            val k = if (i >= 0) p.substring(0, i) else p
            val v = if (i >= 0) p.substring(i + 1) else ""
            try {
                m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: Exception) {
            }
        }
        return m
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 5000
            val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
            val first = input.readLine() ?: return
            var len = 0
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("content-length:", true)) {
                    len = h.substring(15).trim().toIntOrNull() ?: 0
                }
            }
            val parts = first.split(" ")
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }
            var body = ""
            if (len in 1..8192) {
                val buf = CharArray(len)
                var n = 0
                while (n < len) {
                    val r = input.read(buf, n, len - n)
                    if (r < 0) break
                    n += r
                }
                body = String(buf, 0, n)
            }
            val form = if (method == "POST") parse(body) else parse(path.substringAfter('?', ""))
            val out = c.getOutputStream()
            if (form["k"] != token) {
                send(out, 403, page("Mã không hợp lệ. Hãy quét lại mã QR trên tivi."))
            } else if (method == "POST") {
                val u = (form["url"] ?: "").trim()
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    onUrl(u)
                    send(out, 200, page("Đã gửi link sang tivi. Tivi đang tải danh sách kênh, anh có thể đóng trang này."))
                } else {
                    send(out, 400, page("Link phải bắt đầu bằng http:// hoặc https://. Hãy quay lại và dán lại."))
                }
            } else {
                send(out, 200, formPage())
            }
        } catch (e: Exception) {
        } finally {
            try {
                c.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun send(out: java.io.OutputStream, code: Int, html: String) {
        val b = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(b)
        out.flush()
    }

    private val css = "body{margin:0;padding:24px;background:#0F1318;color:#F2F4F7;font-family:sans-serif;font-size:18px}" +
            "h1{font-size:24px;margin:0 0 16px}textarea{width:100%;box-sizing:border-box;height:140px;padding:12px;" +
            "border-radius:12px;border:0;background:#1A2029;color:#F2F4F7;font-size:16px}" +
            "button{margin-top:16px;width:100%;padding:16px;border:0;border-radius:12px;background:#FFB547;" +
            "color:#1A1203;font-weight:700;font-size:18px}p{color:#A8B1BD;line-height:1.5}"

    private fun page(msg: String) =
        "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>QTV</title><style>$css</style></head><body><h1>QTV</h1><p>" +
                esc(msg) + "</p></body></html>"

    private fun formPage() =
        "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>QTV</title><style>$css</style></head><body>" +
                "<h1>Đổi nguồn kênh</h1><p>Dán link playlist M3U rồi bấm Gửi sang tivi.</p>" +
                "<form method=\"POST\" action=\"/\"><input type=\"hidden\" name=\"k\" value=\"" + esc(token) + "\">" +
                "<textarea name=\"url\" placeholder=\"https://...\" autocomplete=\"off\">" + esc(current()) + "</textarea>" +
                "<button type=\"submit\">Gửi sang tivi</button></form></body></html>"
}
