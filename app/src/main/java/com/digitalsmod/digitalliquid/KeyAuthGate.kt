package com.digitalsmod.digitalliquid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Mandatory key gate. Call from the entry Activity before rendering the app or starting services.
 * A cold process always asks the user to type a key; successful verification is kept in memory only
 * for the remaining valid session and is never trusted from local storage on the next app launch.
 */
object KeyAuthGate {
    private const val API_BASE = "https://asia-southeast1-quocdat-396fe.cloudfunctions.net/keyApi"
    // Canonical GitHub Pages route backed by get-key/index.html.
    private const val KEY_PAGE_BASE = "https://digitalsmod.site/get-key"
    private const val PREFS = "digitalsmod_key_auth"
    private const val INSTALL_ID = "install_id"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Authorization is deliberately process-only: after a cold start the user must type a key again.
    @Volatile private var authorizedInstallId: String? = null
    @Volatile private var authorizedUntilMs: Long = 0L

    fun isAuthorizedInCurrentProcess(context: Context): Boolean {
        val installId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(INSTALL_ID, null)
            ?: return false
        return authorizedInstallId == installId && System.currentTimeMillis() < authorizedUntilMs
    }

    fun requireKey(activity: Activity, onAuthorized: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val installId = prefs.getString(INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(INSTALL_ID, it).apply()
        }

        // Avoid asking again for Activity recreation/media picker within the already authorized process.
        if (authorizedInstallId == installId && System.currentTimeMillis() < authorizedUntilMs) {
            onAuthorized()
            return
        }
        showGate(activity, installId, onAuthorized, "Bắt buộc nhập key hợp lệ để vào DigitalLiquid.")
    }

    private fun showGate(activity: Activity, installId: String, onAuthorized: () -> Unit, initialMessage: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            background = rounded(Color.rgb(19, 18, 25), dp(22))
        }
        val subtitle = TextView(activity).apply {
            text = "DIGITALSMOD · ACCESS CONTROL"
            setTextColor(Color.rgb(183, 151, 255))
            textSize = 11f
            letterSpacing = .12f
        }
        val body = TextView(activity).apply {
            text = "Nhập key để mở DigitalLiquid. Chưa có key? Nhấn Get Key để mở trang xác thực trên trình duyệt."
            setTextColor(Color.rgb(196, 191, 207))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(15))
        }
        val input = EditText(activity).apply {
            hint = "DL-XXXXXX-XXXXXX-XXXXXX-XXXXXX"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 115, 132))
            textSize = 14f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(31, 29, 40), dp(13), Color.rgb(67, 59, 86))
        }
        val status = TextView(activity).apply {
            text = initialMessage
            setTextColor(Color.rgb(164, 157, 180))
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(subtitle)
        container.addView(body)
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(status)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Xác thực DigitalLiquid")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Xác nhận", null)
            .setNegativeButton("Get Key", null)
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typedKey = input.text?.toString()?.trim().orEmpty()
                if (typedKey.isBlank()) {
                    status.text = "Bạn chưa nhập key."
                    return@setOnClickListener
                }
                status.text = "Đang xác thực với máy chủ…"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                executor.execute {
                    val result = runCatching {
                        postJson("/verify-key", JSONObject().put("key", typedKey).put("installId", installId))
                    }.getOrElse { JSONObject().put("valid", false).put("reason", "Không kết nối được máy chủ. Kiểm tra Internet và thử lại.") }
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        val valid = result.optBoolean("valid", false)
                        val expiresAt = result.optLong("expiresAt", 0L)
                        if (valid && expiresAt > System.currentTimeMillis()) {
                            authorizedInstallId = installId
                            authorizedUntilMs = expiresAt
                            dialog.dismiss()
                            onAuthorized()
                        } else {
                            status.text = if (valid) "Máy chủ không trả về thời hạn key hợp lệ." else result.optString("reason", "Key không hợp lệ.")
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                status.text = "Đang tạo phiên lấy key…"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                executor.execute {
                    val result = runCatching {
                        postJson("/create-session", JSONObject().put("installId", installId))
                    }.getOrElse { JSONObject().put("ok", false).put("error", "Không kết nối được máy chủ. Kiểm tra Internet và thử lại.") }
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        val sessionId = result.optString("sessionId")
                        if (result.optBoolean("ok", false) && sessionId.matches(Regex("^[a-fA-F0-9]{48}$"))) {
                            // Use the exact extensionless route requested for the GitHub Pages site.
                            // Do not trust a stale webUrl returned by an older deployed backend.
                            val webUrl = "$KEY_PAGE_BASE?sid=${Uri.encode(sessionId)}"
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))) }
                                .onFailure { status.text = "Không mở được trình duyệt trên thiết bị này." }
                        } else {
                            status.text = result.optString("error", "Không tạo được phiên Get Key.")
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(rounded(Color.rgb(19, 18, 25), dp(24), Color.rgb(63, 54, 82)))
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun rounded(color: Int, radiusPx: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
        if (stroke != null) setStroke(1, stroke)
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val connection = (URL(API_BASE.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("Empty server response (HTTP $status)")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
