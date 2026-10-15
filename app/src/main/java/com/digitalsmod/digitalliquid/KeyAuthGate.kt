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
import android.widget.CheckBox
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
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Mandatory key gate. Call from the entry Activity before rendering the app or starting services.
 * A cold process always asks the user to type a key; successful verification is kept in memory only
 * for the remaining valid session and is never trusted from local storage on the next app launch.
 */
object KeyAuthGate {
    private const val FIREBASE_API_KEY = "AIzaSyAVuB8m14zkMPs-JTLAKB0Bp18QBKAMBt4"
    private const val AUTH_SIGNUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_API_KEY"
    private const val DATABASE_BASE = "https://ideo-dd376-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val GET_KEY_URL = "https://digitalsmod.site/get-key/"
    private const val PREFS = "digitalsmod_key_auth"
    private const val INSTALL_ID = "install_id"
    private const val SAVED_KEY = "saved_key"
    private const val SAVE_KEY_ENABLED = "save_key_enabled"
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

    /** Revalidates a key only when the user explicitly enabled saving it, for opt-in boot restore. */
    fun reauthenticateSavedKey(context: Context, callback: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installId = prefs.getString(INSTALL_ID, null).orEmpty()
        val savedKey = if (prefs.getBoolean(SAVE_KEY_ENABLED, false)) prefs.getString(SAVED_KEY, null).orEmpty() else ""
        if (installId.isBlank() || savedKey.isBlank()) {
            mainHandler.post { callback(false) }
            return
        }
        executor.execute {
            val result = runCatching { verifyKeyInRealtimeDatabase(savedKey, installId) }.getOrElse { JSONObject().put("valid", false) }
            val valid = result.optBoolean("valid", false) &&
                (result.optBoolean("permanent", false) || result.optLong("expiresAt", 0L) > System.currentTimeMillis())
            if (valid) {
                authorizedInstallId = installId
                authorizedUntilMs = if (result.optBoolean("permanent", false)) Long.MAX_VALUE else result.optLong("expiresAt", 0L)
            }
            mainHandler.post { callback(valid) }
        }
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
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
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
            if (prefs.getBoolean(SAVE_KEY_ENABLED, false)) {
                setText(prefs.getString(SAVED_KEY, "").orEmpty())
            }
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 115, 132))
            textSize = 14f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(31, 29, 40), dp(13), Color.rgb(67, 59, 86))
        }
        val saveKeyToggle = CheckBox(activity).apply {
            text = "Lưu key trên thiết bị"
            textSize = 12f
            setTextColor(Color.rgb(220, 216, 230))
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(183, 151, 255))
            isChecked = prefs.getBoolean(SAVE_KEY_ENABLED, false)
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
        container.addView(saveKeyToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
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
                        verifyKeyInRealtimeDatabase(typedKey, installId)
                    }.getOrElse { JSONObject().put("valid", false).put("reason", it.message ?: "Không kết nối được Firebase. Kiểm tra Internet và Database Rules.") }
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        val valid = result.optBoolean("valid", false)
                        val permanent = result.optBoolean("permanent", false)
                        val expiresAt = result.optLong("expiresAt", 0L)
                        if (valid && (permanent || expiresAt > System.currentTimeMillis())) {
                            if (saveKeyToggle.isChecked) {
                                prefs.edit().putBoolean(SAVE_KEY_ENABLED, true).putString(SAVED_KEY, typedKey).apply()
                            } else {
                                prefs.edit().putBoolean(SAVE_KEY_ENABLED, false).remove(SAVED_KEY).apply()
                            }
                            authorizedInstallId = installId
                            authorizedUntilMs = if (permanent) Long.MAX_VALUE else expiresAt
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
                    val webUrl = GET_KEY_URL + "?installId=" + Uri.encode(installId)
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))) }
                            .onFailure { status.text = "Không mở được trình duyệt trên thiết bị này." }
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

    private fun verifyKeyInRealtimeDatabase(typedKey: String, installId: String): JSONObject {
        val normalizedKey = typedKey.trim().uppercase(Locale.US)
        val tokenResponse = requestJson(
            AUTH_SIGNUP_URL,
            JSONObject().put("returnSecureToken", true),
            "POST"
        )
        val idToken = tokenResponse.optString("idToken")
        if (idToken.isBlank()) throw IllegalStateException("Firebase không cấp được phiên đăng nhập ẩn danh. Hãy bật Anonymous trong Authentication.")

        val keyHash = sha256(normalizedKey)
        val authToken = URLEncoder.encode(idToken, "UTF-8")
        val keyUrl = "$DATABASE_BASE/keySystem/keys/$keyHash.json?auth=$authToken"
        val keyRecord = requestJson(keyUrl, null, "GET")
        if (keyRecord.length() == 0 || keyRecord == JSONObject.NULL) {
            return JSONObject().put("valid", false).put("reason", "Key không tồn tại hoặc đã bị thu hồi.")
        }
        if (keyRecord.optString("status") != "active") {
            return JSONObject().put("valid", false).put("reason", "Key đã bị vô hiệu hóa.")
        }
        val expiresAt = keyRecord.optLong("expiresAt", 0L)
        val permanent = keyRecord.optBoolean("permanent", false) ||
            keyRecord.optString("plan").equals("permanent", ignoreCase = true)
        if (!permanent && (expiresAt <= 0L || expiresAt <= System.currentTimeMillis())) {
            return JSONObject().put("valid", false).put("reason", "Key đã hết hạn.")
        }
        val expectedDeviceHash = sha256(installId.trim())
        if (keyRecord.optString("deviceHash") != expectedDeviceHash) {
            return JSONObject().put("valid", false).put("reason", "Key này được cấp cho một mã cài đặt khác.")
        }
        return JSONObject()
            .put("valid", true)
            .put("permanent", permanent)
            .put("expiresAt", if (permanent) Long.MAX_VALUE else expiresAt)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun requestJson(url: String, body: JSONObject?, method: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("Firebase trả về phản hồi rỗng (HTTP $status). Kiểm tra Anonymous Auth và Database Rules.")
            val json = if (text.trim() == "null") JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                val errorMessage = json.optJSONObject("error")?.optString("message").orEmpty()
                throw IllegalStateException(when {
                    errorMessage.contains("PERMISSION_DENIED", true) -> "Firebase từ chối quyền đọc key. Kiểm tra Realtime Database Rules."
                    errorMessage.contains("OPERATION_NOT_ALLOWED", true) -> "Chưa bật đăng nhập ẩn danh trong Firebase Authentication."
                    else -> "Firebase trả HTTP $status: ${errorMessage.ifBlank { "yêu cầu thất bại" }}"
                })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

}
