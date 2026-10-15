package com.digitalsmod.digitalliquid

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Talks only to the user's HTTPS proxy. Never embeds an OpenRouter secret in the APK. */
object OpenRouterClient {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun chat(context: Context, model: String, prompt: String, callback: (String?, String?) -> Unit) =
        chatMessages(context, model, listOf("user" to prompt), callback)

    fun chatMessages(
        context: Context,
        model: String,
        messages: List<Pair<String, String>>,
        callback: (String?, String?) -> Unit
    ) {
        val endpoint = AppPrefs.get(context).getString(AppPrefs.AI_ENDPOINT, "").orEmpty().trim()
        if (endpoint.isBlank()) {
            callback(null, "Chưa cấu hình AI proxy. Hãy mở DigitalLiquid → Trợ lý AI → Cấu hình máy chủ.")
            return
        }
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            callback(null, "AI proxy phải dùng HTTPS.")
            return
        }
        if (messages.none { it.first == "user" && it.second.isNotBlank() }) {
            callback(null, "Hãy nhập câu hỏi trước khi gửi.")
            return
        }
        executor.execute {
            var answer: String? = null
            var error: String? = null
            try {
                val idToken = OnlineControlManager.getAuthTokenForRequest(context.applicationContext)
                answer = request(endpoint, model, messages.takeLast(8).map { it.first to it.second.take(6000) }, idToken)
            } catch (t: Throwable) {
                error = t.message ?: "Không thể kết nối AI proxy."
            }
            val a = answer
            val e = error
            main.post { callback(a, e) }
        }
    }

    private fun request(endpoint: String, model: String, messages: List<Pair<String, String>>, firebaseIdToken: String): String {
        val urlText = endpoint.trimEnd('/').let { if (it.endsWith("/api/chat")) it else "$it/api/chat" }
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $firebaseIdToken")
        }
        try {
            val messageArray = JSONArray()
            messages.forEach { (role, content) ->
                if (role == "user" || role == "assistant") {
                    messageArray.put(JSONObject().put("role", role).put("content", content.take(6000)))
                }
            }
            val request = JSONObject().put("model", model).put("messages", messageArray)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(request.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject().put("raw", body.take(400)) }
            if (status !in 200..299 || json.optBoolean("ok", true).not()) {
                val message = json.optString("error").ifBlank { json.optString("message") }.ifBlank { "HTTP $status" }
                throw IllegalStateException("AI proxy: $message")
            }
            val direct = json.optString("text")
            if (direct.isNotBlank()) return direct
            val choices = json.optJSONArray("choices")
            val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) throw IllegalStateException("AI chưa trả về nội dung. Hãy kiểm tra mô hình hoặc hạn mức dịch vụ.")
            return content
        } finally {
            connection.disconnect()
        }
    }
}
