package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LocalApkServer(
    private val context: Context,
    private val controller: AppManagerController,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val token = ByteArray(3).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private var socket: ServerSocket? = null
    private var serverThread: Thread? = null

    val url: String?
        get() = socket?.let { "http://${localIpv4Address()}:${it.localPort}/session/$token" }

    fun start(): String {
        if (running.get()) return requireNotNull(url)
        val server = ServerSocket(0).apply { reuseAddress = true }
        socket = server
        running.set(true)
        serverThread = Thread({
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { handle(client) }
                    .onFailure {
                        if (it !is SocketException) {
                            onStatus("Ошибка веб-сервера: ${it.message}")
                        }
                    }
            }
        }, "yaos-apk-web").apply {
            isDaemon = true
            start()
        }
        return requireNotNull(url)
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        serverThread = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 180_000
        client.use { connection ->
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val requestLine = readLine(input, MAX_HEADER_LINE) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(output, 400, "Bad request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val path = parts[1].substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input, MAX_HEADER_LINE) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            when {
                method == "GET" && path == "/session/$token" ->
                    respond(output, 200, page(), "text/html; charset=utf-8")
                method == "GET" && path == "/favicon.ico" ->
                    respond(output, 204, "")
                method == "POST" && path == "/upload/$token" ->
                    receiveApk(input, output, headers)
                method == "POST" && path == "/url/$token" ->
                    receiveUrl(input, output, headers)
                else ->
                    respond(output, 404, "Not found")
            }
        }
    }

    private fun receiveApk(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        headers: Map<String, String>,
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull()
            ?: run {
                respond(output, 411, "Content-Length required")
                return
            }
        if (contentLength !in 1..MAX_APK_BYTES) {
            respond(output, 413, "APK is too large")
            return
        }
        val encodedName = headers["x-filename"].orEmpty()
        val requestedName = runCatching {
            URLDecoder.decode(encodedName, Charsets.UTF_8.name())
        }.getOrDefault("phone-upload.apk")
        val fileName = safeFileName(requestedName)
            .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
        val destination = File(context.cacheDir, "web-${System.currentTimeMillis()}-$fileName")
        onStatus("Получение $fileName с телефона…")
        var lastProgressBucket = -1
        FileOutputStream(destination).use { target ->
            copyExactly(input, target, contentLength) { copied ->
                val percent = (copied * 100 / contentLength).toInt().coerceIn(0, 100)
                val bucket = percent / 5
                if (bucket != lastProgressBucket) {
                    lastProgressBucket = bucket
                    onStatus("Получение $fileName: $percent%")
                }
            }
        }
        onStatus("Проверка APK и выбор способа установки…")
        val result = controller.installApkBlocking(destination)
        onStatus(result.message)
        respond(output, if (result.success) 200 else 500, result.message)
    }

    private fun receiveUrl(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        headers: Map<String, String>,
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull()
            ?: run {
                respond(output, 411, "Content-Length required")
                return
            }
        if (contentLength !in 1..MAX_URL_BYTES) {
            respond(output, 413, "URL is too long")
            return
        }
        val body = ByteArray(contentLength.toInt())
        readExactly(input, body)
        val apkUrl = body.toString(Charsets.UTF_8).trim()
        onStatus("Загрузка APK по ссылке с телефона…")
        val result = controller.installUrlBlocking(apkUrl, onStatus)
        onStatus(result.message)
        respond(output, if (result.success) 200 else 500, result.message)
    }

    private fun page(): String =
        """
        <!doctype html>
        <html lang="ru">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>YAOS APK Installer</title>
          <style>
            :root{color-scheme:dark;font-family:system-ui,sans-serif}
            body{margin:0;background:#101317;color:#fff;display:grid;place-items:center;min-height:100vh}
            main{width:min(92vw,560px);background:#1c222b;padding:28px;border-radius:18px}
            h1{margin:0 0 8px;color:#5eb8ff}p{color:#adb9c5}
            section{margin-top:24px;padding-top:20px;border-top:1px solid #36404c}
            input,button{box-sizing:border-box;width:100%;padding:15px;border:0;border-radius:10px;font-size:16px}
            input{background:#101317;color:#fff;margin:8px 0}
            button{background:#2c6794;color:#fff;font-weight:700}
            button:disabled{opacity:.55}
            #status{min-height:24px;color:#81d8a1}
          </style>
        </head>
        <body><main>
          <h1>YAOS APK Installer</h1>
          <p>Телевизор принимает APK напрямую. Страница закроется без сохранения файла после установки.</p>
          <section>
            <strong>APK с телефона</strong>
            <input id="file" type="file" accept=".apk,application/vnd.android.package-archive">
            <button id="upload">Загрузить и установить</button>
          </section>
          <section>
            <strong>APK по ссылке</strong>
            <input id="url" type="url" placeholder="https://example.org/app.apk">
            <button id="installUrl">Скачать на ТВ и установить</button>
          </section>
          <section><div id="status">Готово к загрузке</div></section>
          <script>
            const status=document.querySelector('#status');
            const setBusy=(busy)=>document.querySelectorAll('button').forEach(x=>x.disabled=busy);
            async function send(path,body,headers={}){
              setBusy(true);status.textContent='Передача и установка…';
              try{
                const response=await fetch(path,{method:'POST',body,headers});
                const text=await response.text();
                if(!response.ok)throw new Error(text);
                status.textContent=text;
              }catch(error){status.textContent='Ошибка: '+error.message}
              finally{setBusy(false)}
            }
            document.querySelector('#upload').onclick=()=>{
              const file=document.querySelector('#file').files[0];
              if(!file){status.textContent='Сначала выберите APK';return}
              send('/upload/$token',file,{'X-Filename':encodeURIComponent(file.name)});
            };
            document.querySelector('#installUrl').onclick=()=>{
              const url=document.querySelector('#url').value.trim();
              if(!url){status.textContent='Введите ссылку';return}
              send('/url/$token',url,{'Content-Type':'text/plain;charset=utf-8'});
            };
          </script>
        </main></body></html>
        """.trimIndent()

    private fun localIpv4Address(): String {
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { network ->
                Collections.list(network.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { network.name to it.hostAddress.orEmpty() }
            }
        return candidates.minByOrNull { (name, address) ->
            when {
                address.startsWith("10.77.77.") -> 0
                name.startsWith("eth", true) -> 1
                address.startsWith("192.168.") -> 2
                name.startsWith("wlan", true) -> 3
                else -> 4
            }
        }?.second ?: "127.0.0.1"
    }

    private fun readLine(input: BufferedInputStream, maxLength: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maxLength) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        check(bytes.size < maxLength) { "HTTP header is too long" }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun copyExactly(
        input: BufferedInputStream,
        output: FileOutputStream,
        length: Long,
        progress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        var copied = 0L
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count > 0) { "Соединение оборвалось во время загрузки" }
            output.write(buffer, 0, count)
            remaining -= count
            copied += count
            progress(copied)
        }
    }

    private fun readExactly(input: BufferedInputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = input.read(destination, offset, destination.size - offset)
            check(count > 0) { "Соединение оборвалось" }
            offset += count
        }
    }

    private fun respond(
        output: BufferedOutputStream,
        code: Int,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        output.write(
            (
                "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        output.write(bytes)
        output.flush()
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "phone-upload.apk" }

    companion object {
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_URL_BYTES = 8 * 1024L
        private const val MAX_APK_BYTES = 1_500_000_000L
    }
}
