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
        val lightMode = AppPrefs.get(activity).getBoolean(AppPrefs.LIGHT_MODE, false)
        fun dp(value: Int) = (value * density).toInt()

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val body = TextView(activity).apply {
            text = "Nhập key để mở DigitalLiquid. Nếu chưa có key, bạn có thể mở trang cấp key ngay từ đây."
            setTextColor(if (lightMode) Color.rgb(82, 99, 114) else Color.rgb(196, 204, 219))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1.04f)
            setPadding(0, 0, 0, dp(13))
        }
        val input = EditText(activity).apply {
            hint = "DL-XXXXXX-XXXXXX-XXXXXX-XXXXXX"
            if (prefs.getBoolean(SAVE_KEY_ENABLED, false)) setText(prefs.getString(SAVED_KEY, "").orEmpty())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(if (lightMode) Color.rgb(24, 39, 53) else Color.WHITE)
            setHintTextColor(if (lightMode) Color.rgb(117, 132, 151) else Color.rgb(130, 140, 160))
            textSize = 13f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(
                if (lightMode) Color.rgb(242, 247, 250) else Color.rgb(24, 29, 42),
                dp(14), if (lightMode) Color.rgb(139, 160, 179) else Color.rgb(79, 94, 119)
            )
        }
        val saveKeyToggle = CheckBox(activity).apply {
            text = "Lưu key trên thiết bị"
            textSize = 12f
            setTextColor(if (lightMode) Color.rgb(48, 63, 77) else Color.rgb(216, 222, 235))
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(109, 226, 202))
            isChecked = prefs.getBoolean(SAVE_KEY_ENABLED, false)
        }
        val status = TextView(activity).apply {
            text = initialMessage
            setTextColor(if (lightMode) Color.rgb(22, 126, 116) else Color.rgb(160, 218, 207))
            textSize = 11.5f
            setPadding(0, dp(9), 0, 0)
        }
        form.addView(body)
        form.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(saveKeyToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        form.addView(status)

        var busy = false
        val dialog = LiquidDialog.show(
            activity = activity,
            eyebrow = "DIGITALSMOD · SECURE ACCESS",
            title = "Mở khóa DigitalLiquid",
            content = form,
            cancelable = false,
            actions = listOf(
                LiquidDialog.Action("Lấy key", primary = false, dismissOnClick = false) { activeDialog ->
                    if (busy) return@Action
                    val webUrl = GET_KEY_URL + "?installId=" + Uri.encode(installId)
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                        activeDialog.dismiss()
                    } catch (_: Exception) {
                        status.text = "Không mở được trình duyệt trên thiết bị này."
                    }
                },
                LiquidDialog.Action("Xác nhận", primary = true, dismissOnClick = false) { activeDialog ->
                    if (busy) return@Action
                    val typedKey = input.text?.toString()?.trim().orEmpty()
                    if (typedKey.isBlank()) {
                        status.text = "Bạn chưa nhập key."
                        input.error = "Nhập key trước khi tiếp tục"
                        return@Action
                    }
                    busy = true
                    input.error = null
                    status.text = "Đang xác thực key…"
                    LiquidDialog.setActionEnabled(activeDialog, "Xác nhận", false)
                    LiquidDialog.setActionEnabled(activeDialog, "Lấy key", false)
                    executor.execute {
                        val result = runCatching { verifyKeyInRealtimeDatabase(typedKey, installId) }
                            .getOrElse { JSONObject().put("valid", false).put("reason", it.message ?: "Không kết nối được dịch vụ xác thực. Hãy kiểm tra mạng rồi thử lại." ) }
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
                                activeDialog.dismiss()
                                onAuthorized()
                            } else {
                                busy = false
                                status.text = if (valid) "Máy chủ không trả về thời hạn key hợp lệ." else result.optString("reason", "Key không hợp lệ.")
                                LiquidDialog.setActionEnabled(activeDialog, "Xác nhận", true)
                                LiquidDialog.setActionEnabled(activeDialog, "Lấy key", true)
                            }
                        }
                    }
                }
            )
        )
        dialog.setCanceledOnTouchOutside(false)
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
        if (idToken.isBlank()) throw IllegalStateException("Không thể khởi tạo phiên xác thực. Hãy kiểm tra kết nối hoặc thử lại sau.")

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
            if (text.isBlank()) throw IllegalStateException("Dịch vụ xác thực trả về phản hồi rỗng (HTTP $status). Hãy thử lại sau.")
            val json = if (text.trim() == "null") JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                val errorMessage = json.optJSONObject("error")?.optString("message").orEmpty()
                throw IllegalStateException(when {
                    errorMessage.contains("PERMISSION_DENIED", true) -> "Dịch vụ xác thực từ chối yêu cầu. Hãy kiểm tra key hoặc liên hệ quản trị viên."
                    errorMessage.contains("OPERATION_NOT_ALLOWED", true) -> "Dịch vụ xác thực chưa sẵn sàng. Hãy thử lại sau."
                    else -> "Xác thực thất bại (HTTP $status): ${errorMessage.ifBlank { "yêu cầu thất bại" }}"
                })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

}
