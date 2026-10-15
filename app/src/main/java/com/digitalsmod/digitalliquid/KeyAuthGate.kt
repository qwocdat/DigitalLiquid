package com.digitalsmod.digitalliquid

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

        val dialog = android.app.Dialog(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFEFFFF.toInt(), 0xFFF0F3FA.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x668795B1)
            }
        }
        panel.addView(View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF9A8CF6.toInt(), 0xFF6256BB.toInt())
            ).apply { cornerRadius = dp(4).toFloat() }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { bottomMargin = dp(17) })
        panel.addView(TextView(activity).apply {
            text = "DIGITALLIQUID  /  ACCESS"
            setTextColor(0xFF6256BB.toInt())
            textSize = 10.5f
            letterSpacing = .10f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        panel.addView(TextView(activity).apply {
            text = "Mở khóa ứng dụng"
            setTextColor(0xFF202333.toInt())
            textSize = 24f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(TextView(activity).apply {
            text = "Nhập mã truy cập để tiếp tục. Mã được xác minh trực tuyến và không cần tạo tài khoản cá nhân."
            setTextColor(0xFF62697A.toInt())
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1.02f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(15) })
        val input = EditText(activity).apply {
            hint = "DL-XXXXXX-XXXXXX-XXXXXX-XXXXXX"
            if (prefs.getBoolean(SAVE_KEY_ENABLED, false)) setText(prefs.getString(SAVED_KEY, "").orEmpty())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(0xFF222638.toInt())
            setHintTextColor(0xFF9298A8.toInt())
            textSize = 13.5f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(0xFFF9FAFD.toInt(), dp(14), 0x668795B1)
        }
        panel.addView(input, LinearLayout.LayoutParams(-1, -2))
        val saveKeyToggle = CheckBox(activity).apply {
            text = "Lưu mã trên thiết bị"
            textSize = 12f
            setTextColor(0xFF555D70.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF7968DD.toInt())
            isChecked = prefs.getBoolean(SAVE_KEY_ENABLED, false)
        }
        panel.addView(saveKeyToggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val status = TextView(activity).apply {
            text = initialMessage
            setTextColor(0xFF737B8D.toInt())
            textSize = 11.5f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val getKeyButton = IOSButton(activity).apply {
            style("Lấy mã", false)
        }
        val confirmButton = IOSButton(activity).apply {
            style("Xác nhận", true)
        }
        actions.addView(getKeyButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        actions.addView(confirmButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(17) })

        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(panel)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((activity.resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        confirmButton.setOnClickListener {
            val typedKey = input.text?.toString()?.trim().orEmpty()
            if (typedKey.isBlank()) {
                status.text = "Bạn chưa nhập mã truy cập."
                return@setOnClickListener
            }
            status.text = "Đang xác minh…"
            confirmButton.isEnabled = false
            getKeyButton.isEnabled = false
            executor.execute {
                val result = runCatching { verifyKeyInRealtimeDatabase(typedKey, installId) }
                    .getOrElse { JSONObject().put("valid", false).put("reason", it.message ?: "Không kết nối được. Hãy kiểm tra Internet.") }
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
                        status.text = if (valid) "Máy chủ không trả về thời hạn hợp lệ." else result.optString("reason", "Mã không hợp lệ.")
                        confirmButton.isEnabled = true
                        getKeyButton.isEnabled = true
                    }
                }
            }
        }
        getKeyButton.setOnClickListener {
            status.text = "Đang mở trang lấy mã…"
            confirmButton.isEnabled = false
            getKeyButton.isEnabled = false
            val webUrl = GET_KEY_URL + "?installId=" + Uri.encode(installId)
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))) }
                .onFailure { status.text = "Không mở được trình duyệt trên thiết bị này." }
            confirmButton.isEnabled = true
            getKeyButton.isEnabled = true
        }
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
        if (idToken.isBlank()) throw IllegalStateException("Không thể tạo phiên xác minh. Hãy kiểm tra kết nối và thử lại.")

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
            if (text.isBlank()) throw IllegalStateException("Dịch vụ xác minh trả về phản hồi rỗng (HTTP $status). Hãy thử lại sau.")
            val json = if (text.trim() == "null") JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                val errorMessage = json.optJSONObject("error")?.optString("message").orEmpty()
                throw IllegalStateException(when {
                    errorMessage.contains("PERMISSION_DENIED", true) -> "Dịch vụ xác minh từ chối yêu cầu. Hãy kiểm tra mã truy cập hoặc thử lại sau."
                    errorMessage.contains("OPERATION_NOT_ALLOWED", true) -> "Dịch vụ xác minh chưa sẵn sàng. Hãy thử lại sau."
                    else -> "Dịch vụ xác minh trả HTTP $status: ${errorMessage.ifBlank { "yêu cầu thất bại" }}"
                })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

}
