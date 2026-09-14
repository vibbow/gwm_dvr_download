package net.vsean.gwm_dvr_download

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.math.min

data class RemoteFile(
    val name: String,
    val path: String,
    val size: Long,
    val id: Int,
) {
    /** fileName 字段不带扩展名，所以用 filePath 的最后一段作为保存文件名。 */
    val displayName: String get() = path.substringAfterLast('/').ifEmpty { name }
}

interface DashcamEvents {
    fun onStatus(text: String)
    fun onLog(text: String)
    fun onFileList(files: List<RemoteFile>)
    fun onProgress(index: Int, count: Int, file: RemoteFile, received: Long, total: Long)
}

interface FileSink {
    fun open(file: RemoteFile): OutputStream
    fun commit(file: RemoteFile)
    fun abort(file: RemoteFile)
}

data class DownloadResult(val total: Int, val succeeded: Int)

/**
 * 车机协议（TCP，每条 JSON 消息以换行结尾）：
 *   手机 -> {"requestCode":1001,"phoneName":"..."}\r\n
 *   车机 <- {"fileList":[...],"requestCode":1008,"statusCode":0}\n      等待用户在车机上确认
 *   车机 <- {"fileList":[...],"requestCode":1001,"statusCode":200}\n    已确认，给出文件列表
 * 对每个文件：
 *   手机 -> {"filePath":"...","requestCode":1002}\r\n
 *   车机 <- {"file":{...,"fileSize":N},"requestCode":1002,"statusCode":200}\n
 *   手机 -> {"filePath":"...","requestCode":1003}\r\n                  开始传输
 *   车机 <- N 字节原始文件内容
 *   手机 -> {"filePath":"...","requestCode":2001}\r\n                  接收完成
 */
class DashcamClient(
    private val socketFactory: SocketFactory,
    private val host: String,
    private val port: Int,
    private val phoneName: String,
    private val events: DashcamEvents,
    private val sink: FileSink,
) {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    /** 从任意线程调用，用于中断正在进行的阻塞读。 */
    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    fun run(): DownloadResult {
        val s = socketFactory.createSocket()
        socket = s
        if (closed) s.close()
        s.use {
            events.onStatus("正在连接记录仪 …")
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20

            val input = BufferedInputStream(s.getInputStream(), 256 * 1024)
            val output = s.getOutputStream()

            send(output, "{\"requestCode\":1001,\"phoneName\":${quote(phoneName)}}")
            events.onStatus("已连接，等待车机响应 …")

            s.soTimeout = CONFIRM_TIMEOUT_MS
            val files = waitForFileList(input)
            events.onFileList(files)
            if (files.isEmpty()) {
                events.onStatus("车机没有发送任何文件")
                return DownloadResult(0, 0)
            }

            s.soTimeout = DATA_TIMEOUT_MS
            val buffer = ByteArray(256 * 1024)
            var succeeded = 0
            files.forEachIndexed { index, file ->
                if (downloadOne(input, output, buffer, index, files.size, file)) succeeded++
            }
            return DownloadResult(files.size, succeeded)
        }
    }

    private fun waitForFileList(input: InputStream): List<RemoteFile> {
        while (true) {
            val msg = readJson(input)
            val code = msg.optInt("requestCode", -1)
            val status = msg.optInt("statusCode", -1)
            when {
                code == 1008 -> {
                    val n = msg.optJSONArray("fileList")?.length() ?: 0
                    events.onStatus("请在行车记录仪屏幕上点击确认（$n 个文件）")
                }
                code == 1001 && status == 200 -> return parseFileList(msg)
                code == 1001 -> throw IOException("车机拒绝了请求（statusCode=$status）")
                else -> events.onLog("忽略消息：${msg.toString().take(200)}")
            }
        }
    }

    private fun downloadOne(
        input: InputStream,
        output: OutputStream,
        buffer: ByteArray,
        index: Int,
        count: Int,
        file: RemoteFile,
    ): Boolean {
        send(output, "{\"filePath\":${quote(file.path)},\"requestCode\":1002}")

        var header: JSONObject
        do {
            header = readJson(input)
            if (header.optInt("requestCode", -1) != 1002) {
                events.onLog("忽略消息：${header.toString().take(200)}")
            }
        } while (header.optInt("requestCode", -1) != 1002)

        val status = header.optInt("statusCode", -1)
        if (status != 200) {
            events.onLog("跳过 ${file.displayName}：statusCode=$status")
            return false
        }
        val size = header.optJSONObject("file")?.optLong("fileSize", file.size) ?: file.size

        val out = sink.open(file)
        send(output, "{\"filePath\":${quote(file.path)},\"requestCode\":1003}")
        var ok = false
        try {
            var received = 0L
            var lastReport = 0L
            events.onProgress(index, count, file, 0, size)
            while (received < size) {
                val n = input.read(buffer, 0, min(buffer.size.toLong(), size - received).toInt())
                if (n < 0) throw EOFException("传输中断（已收到 $received / $size 字节）")
                out.write(buffer, 0, n)
                received += n
                val now = System.currentTimeMillis()
                if (now - lastReport >= 200 || received == size) {
                    lastReport = now
                    events.onProgress(index, count, file, received, size)
                }
            }
            out.flush()
            ok = true
        } finally {
            runCatching { out.close() }
            if (ok) sink.commit(file) else runCatching { sink.abort(file) }
        }
        send(output, "{\"filePath\":${quote(file.path)},\"requestCode\":2001}")
        events.onLog("已保存 ${file.displayName}（${formatSize(size)}）")
        return true
    }

    private fun parseFileList(msg: JSONObject): List<RemoteFile> {
        val arr = msg.optJSONArray("fileList") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteFile(
                name = o.optString("fileName"),
                path = o.getString("filePath"),
                size = o.optLong("fileSize"),
                id = o.optInt("id", i),
            )
        }
    }

    private fun send(output: OutputStream, json: String) {
        output.write((json + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readJson(input: InputStream): JSONObject {
        while (true) {
            val line = readLine(input).trim()
            if (line.isEmpty()) continue
            return try {
                JSONObject(line)
            } catch (e: Exception) {
                throw IOException("无法解析车机响应：${line.take(200)}")
            }
        }
    }

    /** 逐字节读到 '\n'，不能用 BufferedReader，否则会把后面的二进制文件数据也读进缓冲。 */
    private fun readLine(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (closed) throw IOException("已取消")
                throw EOFException("车机断开了连接")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
            if (bos.size() > MAX_LINE_BYTES) throw IOException("车机响应过长")
        }
        return bos.toString("UTF-8")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val CONFIRM_TIMEOUT_MS = 5 * 60_000
        private const val DATA_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 16 * 1024 * 1024

        /** 手工拼 JSON 字符串：org.json 会把 '/' 转义成 "\/"，为稳妥起见与原车 App 保持一致。 */
        fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1 shl 30 -> String.format("%.2f GB", bytes / (1 shl 30).toDouble())
            bytes >= 1 shl 20 -> String.format("%.1f MB", bytes / (1 shl 20).toDouble())
            bytes >= 1 shl 10 -> String.format("%.0f KB", bytes / (1 shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
