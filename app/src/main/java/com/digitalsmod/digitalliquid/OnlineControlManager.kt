package com.digitalsmod.digitalliquid

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Reads the public app-control document from Firebase Realtime Database.
 * The API key is a Firebase client identifier, not an admin secret. Database rules must restrict writes.
 */
object OnlineControlManager {
    private const val FIREBASE_API_KEY = "AIzaSyAVuB8m14zkMPs-JTLAKB0Bp18QBKAMBt4"
    private const val DATABASE_BASE = "https://ideo-dd376-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val AUTH_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_API_KEY"
    private const val REFRESH_URL = "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY"
    private const val AUTH_PREFS = "digitalliquid_online_control_auth"
    private const val ID_TOKEN = "id_token"
    private const val REFRESH_TOKEN = "refresh_token"
    private const val TOKEN_EXPIRY = "token_expiry"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class RemoteControl(
        val configFound: Boolean = false,
        val appEnabled: Boolean = true,
        val maintenanceEnabled: Boolean = false,
        val maintenanceMessage: String = "DigitalLiquid đang được bảo trì. Vui lòng thử lại sau.",
        val announcementEnabled: Boolean = false,
        val announcementId: String = "",
        val announcementTitle: String = "Thông báo DigitalLiquid",
        val announcementMessage: String = "",
        val announcementUrl: String = "",
        val latestVersionCode: Int = 0,
        val latestVersionName: String = "",
        val minVersionCode: Int = 0,
        val downloadUrl: String = "",
        val expectedSignerSha256: String = "",
        val signerMatches: Boolean = true
    ) {
        fun blocksUse(localVersionCode: Int): Boolean =
            !appEnabled || maintenanceEnabled || (minVersionCode > localVersionCode && downloadUrl.isNotBlank()) || !signerMatches
    }

    fun fetch(context: Context, callback: (RemoteControl?, String?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var control: RemoteControl? = null
            var error: String? = null
            try {
                control = fetchBlocking(appContext)
            } catch (t: Throwable) {
                error = t.message ?: "Không thể kết nối máy chủ điều khiển."
            }
            val result = control
            val message = error
            mainHandler.post { callback(result, message) }
        }
    }

    private fun fetchBlocking(context: Context): RemoteControl {
        val token = getIdToken(context)
        val auth = URLEncoder.encode(token, "UTF-8")
        val root = requestJson("$DATABASE_BASE/appControl.json?auth=$auth", null, "GET")
        if (root.length() == 0) return RemoteControl()

        val maintenance = root.optJSONObject("maintenance") ?: JSONObject()
        val announcement = root.optJSONObject("announcement") ?: JSONObject()
        val expectedSigner = root.optString("expectedSignerSha256", "").trim().replace(":", "").uppercase()
        val actualSigner = signingCertificateSha256(context).uppercase()
        val signatureMatches = expectedSigner.isBlank() || expectedSigner == actualSigner

        return RemoteControl(
            configFound = true,
            appEnabled = root.optBoolean("appEnabled", root.optBoolean("enabled", true)),
            maintenanceEnabled = maintenance.optBoolean("enabled", root.optBoolean("maintenanceEnabled", false)),
            maintenanceMessage = maintenance.optString("message", root.optString("maintenanceMessage", "DigitalLiquid đang được bảo trì. Vui lòng thử lại sau.")),
            announcementEnabled = announcement.optBoolean("enabled", root.optBoolean("announcementEnabled", false)),
            announcementId = announcement.optString("id", announcement.optString("updatedAt", root.optString("announcementId", ""))),
            announcementTitle = announcement.optString("title", root.optString("announcementTitle", "Thông báo DigitalLiquid")),
            announcementMessage = announcement.optString("message", root.optString("announcementMessage", "")),
            announcementUrl = announcement.optString("url", root.optString("announcementUrl", "")),
            latestVersionCode = root.optInt("latestVersionCode", root.optInt("versionCode", 0)),
            latestVersionName = root.optString("latestVersionName", root.optString("versionName", "")),
            minVersionCode = root.optInt("minVersionCode", 0),
            downloadUrl = root.optString("downloadUrl", "").trim(),
            expectedSignerSha256 = expectedSigner,
            signerMatches = signatureMatches
        )
    }

    private fun getIdToken(context: Context): String {
        val prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = prefs.getString(ID_TOKEN, null).orEmpty()
        if (cached.isNotBlank() && prefs.getLong(TOKEN_EXPIRY, 0L) > now + 60_000L) return cached

        val refresh = prefs.getString(REFRESH_TOKEN, null).orEmpty()
        val json = if (refresh.isNotBlank()) {
            runCatching {
                requestJson(REFRESH_URL, JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh), "POST")
            }.getOrNull()
        } else null

        val tokenJson = json ?: requestJson(AUTH_URL, JSONObject().put("returnSecureToken", true), "POST")
        val token = tokenJson.optString("id_token")
        val newRefresh = tokenJson.optString("refresh_token")
        if (token.isBlank()) throw IllegalStateException("Firebase không cấp được token để đọc trạng thái ứng dụng.")
        val expiresIn = tokenJson.optString("expires_in", "3600").toLongOrNull() ?: 3600L
        prefs.edit()
            .putString(ID_TOKEN, token)
            .putString(REFRESH_TOKEN, newRefresh.ifBlank { refresh })
            .putLong(TOKEN_EXPIRY, now + expiresIn * 1000L)
            .apply()
        return token
    }

    private fun packageInfo(context: Context): PackageInfo {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(context.packageName, if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES)
    }

    private fun signingCertificateSha256(context: Context): String {
        return runCatching {
            val info = packageInfo(context)
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val bytes = signatures?.firstOrNull()?.toByteArray() ?: return ""
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
        }.getOrDefault("")
    }

    private fun requestJson(url: String, body: JSONObject?, method: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
            val json = if (text.isBlank() || text.trim() == "null") JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                val reason = json.optJSONObject("error")?.optString("message").orEmpty()
                throw IllegalStateException("Firebase HTTP $status${if (reason.isBlank()) "" else ": $reason"}")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
