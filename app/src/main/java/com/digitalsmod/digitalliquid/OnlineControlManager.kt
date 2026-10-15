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
 * Reads public app-control settings from Firebase Realtime Database using
 * Firebase Anonymous Authentication REST API.
 *
 * IMPORTANT:
 * - FIREBASE_API_KEY and DATABASE_BASE must belong to the SAME Firebase project.
 * - Anonymous sign-in must be enabled in that project's Authentication settings.
 * - Do not use open database write rules as an admin-security mechanism.
 */
object OnlineControlManager {
    private const val FIREBASE_API_KEY = "AIzaSyAVuB8m14zkMPs-JTLAKB0Bp18QBKAMBt4"
    private const val DATABASE_BASE =
        "https://ideo-dd376-default-rtdb.asia-southeast1.firebasedatabase.app"

    private const val AUTH_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_API_KEY"
    private const val REFRESH_URL =
        "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY"

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
            !appEnabled ||
                maintenanceEnabled ||
                (minVersionCode > localVersionCode && downloadUrl.isNotBlank()) ||
                !signerMatches
    }

    /** Call only from a background thread; returns the cached/refreshed Firebase ID token. */
    fun getAuthTokenForRequest(context: Context): String = getIdToken(context.applicationContext)

    fun fetch(context: Context, callback: (RemoteControl?, String?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var result: RemoteControl? = null
            var error: String? = null
            try {
                result = fetchBlocking(appContext)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val finalResult = result
            val finalError = error
            mainHandler.post { callback(finalResult, finalError) }
        }
    }

    @Throws(Exception::class)
    private fun fetchBlocking(context: Context): RemoteControl {
        val token = getIdToken(context)
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val root = requestJson(
            "$DATABASE_BASE/appControl.json?auth=$encodedToken",
            null,
            "GET"
        )

        if (root.length() == 0) return RemoteControl()

        val maintenance = root.optJSONObject("maintenance") ?: JSONObject()
        val announcement = root.optJSONObject("announcement") ?: JSONObject()
        val expectedSigner = root.optString("expectedSignerSha256", "")
            .trim()
            .replace(":", "")
            .uppercase()
        val actualSigner = signingCertificateSha256(context).uppercase()
        val signatureMatches = expectedSigner.isBlank() || expectedSigner == actualSigner

        return RemoteControl(
            configFound = true,
            appEnabled = root.optBoolean("appEnabled", root.optBoolean("enabled", true)),
            maintenanceEnabled = maintenance.optBoolean(
                "enabled",
                root.optBoolean("maintenanceEnabled", false)
            ),
            maintenanceMessage = maintenance.optString(
                "message",
                root.optString(
                    "maintenanceMessage",
                    "DigitalLiquid đang được bảo trì. Vui lòng thử lại sau."
                )
            ),
            announcementEnabled = announcement.optBoolean(
                "enabled",
                root.optBoolean("announcementEnabled", false)
            ),
            announcementId = announcement.optString(
                "id",
                announcement.optString("updatedAt", root.optString("announcementId", ""))
            ),
            announcementTitle = announcement.optString(
                "title",
                root.optString("announcementTitle", "Thông báo DigitalLiquid")
            ),
            announcementMessage = announcement.optString(
                "message",
                root.optString("announcementMessage", "")
            ),
            announcementUrl = announcement.optString(
                "url",
                root.optString("announcementUrl", "")
            ),
            latestVersionCode = root.optInt(
                "latestVersionCode",
                root.optInt("versionCode", 0)
            ),
            latestVersionName = root.optString(
                "latestVersionName",
                root.optString("versionName", "")
            ),
            minVersionCode = root.optInt("minVersionCode", 0),
            downloadUrl = root.optString("downloadUrl", "").trim(),
            expectedSignerSha256 = expectedSigner,
            signerMatches = signatureMatches
        )
    }

    @Throws(Exception::class)
    private fun getIdToken(context: Context): String {
        val prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cachedToken = prefs.getString(ID_TOKEN, null).orEmpty()
        val cachedExpiry = prefs.getLong(TOKEN_EXPIRY, 0L)

        if (cachedToken.isNotBlank() && cachedExpiry > now + 60_000L) {
            return cachedToken
        }

        val oldRefreshToken = prefs.getString(REFRESH_TOKEN, null).orEmpty()
        var refreshFailure: String? = null

        if (oldRefreshToken.isNotBlank()) {
            try {
                val refreshJson = requestJson(
                    REFRESH_URL,
                    JSONObject()
                        .put("grant_type", "refresh_token")
                        .put("refresh_token", oldRefreshToken),
                    "POST"
                )
                val refreshedToken = refreshJson.optString("id_token")
                if (refreshedToken.isNotBlank()) {
                    saveTokens(
                        prefs,
                        refreshedToken,
                        refreshJson.optString("refresh_token").ifBlank { oldRefreshToken },
                        refreshJson.optString("expires_in", "3600").toLongOrNull() ?: 3600L
                    )
                    return refreshedToken
                }
                refreshFailure = describeAuthError(refreshJson)
            } catch (e: Exception) {
                refreshFailure = e.message
            }
        }

        // If the cached refresh token is expired/invalid, clear the stale tokens
        // and obtain a fresh anonymous session.
        val signInJson = try {
            requestJson(
                AUTH_URL,
                JSONObject().put("returnSecureToken", true),
                "POST"
            )
        } catch (e: Exception) {
            val extra = refreshFailure?.let { " (làm mới token trước đó cũng lỗi: $it)" }.orEmpty()
            throw IllegalStateException("${e.message ?: "Không thể xác thực kết nối"}$extra", e)
        }

        val newIdToken = signInJson.optString("idToken")
            .ifBlank { signInJson.optString("id_token") }
        val newRefreshToken = signInJson.optString("refreshToken")
            .ifBlank { signInJson.optString("refresh_token") }

        if (newIdToken.isBlank()) {
            val detail = describeAuthError(signInJson)
            throw IllegalStateException(
                "Không thể xác thực kết nối dịch vụ. Hãy thử lại sau hoặc kiểm tra cấu hình dịch vụ."
            )
        }

        val expiresIn = signInJson.optString("expiresIn")
            .ifBlank { signInJson.optString("expires_in", "3600") }
            .toLongOrNull() ?: 3600L

        saveTokens(prefs, newIdToken, newRefreshToken, expiresIn)
        return newIdToken
    }

    private fun saveTokens(
        prefs: android.content.SharedPreferences,
        idToken: String,
        refreshToken: String,
        expiresInSeconds: Long
    ) {
        prefs.edit()
            .putString(ID_TOKEN, idToken)
            .putString(REFRESH_TOKEN, refreshToken)
            .putLong(TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000L)
            .apply()
    }

    private fun describeAuthError(json: JSONObject): String {
        val errorObject = json.optJSONObject("error")
        val message = errorObject?.optString("message").orEmpty()
        val code = errorObject?.optInt("code", 0) ?: 0
        val detail = json.optString("error_description")
            .ifBlank { json.optString("error") }
            .ifBlank { message }
        return buildString {
            if (code > 0) append("HTTP ").append(code).append(": ")
            append(detail.ifBlank { "Phản hồi máy chủ không chứa dữ liệu xác thực" })
        }
    }

    private fun packageInfo(context: Context): PackageInfo {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                android.content.pm.PackageManager.GET_SIGNATURES
            }
        )
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
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02X".format(it) }
        }.getOrDefault("")
    }

    @Throws(Exception::class)
    private fun requestJson(url: String, body: JSONObject?, method: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseText = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()

            val json = if (responseText.isBlank() || responseText.trim() == "null") {
                JSONObject()
            } else {
                runCatching { JSONObject(responseText) }.getOrElse {
                    JSONObject().put("rawResponse", responseText.take(500))
                }
            }

            if (status !in 200..299) {
                val errorObject = json.optJSONObject("error")
                val reason = errorObject?.optString("message").orEmpty()
                    .ifBlank { json.optString("error_description") }
                    .ifBlank { json.optString("error") }
                    .ifBlank { json.optString("rawResponse") }
                throw IllegalStateException(
                    "Máy chủ trả về mã HTTP $status${if (reason.isBlank()) "" else ": $reason"}"
                )
            }

            return json
        } finally {
            connection.disconnect()
        }
    }
}
