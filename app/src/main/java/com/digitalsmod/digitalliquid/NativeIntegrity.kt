package com.digitalsmod.digitalliquid

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Defense-in-depth signer check. The native library hashes the signing certificate and compares it
 * with a publisher-provided SHA-256 fingerprint. This is not encryption or an unpatchable DRM system.
 */
object NativeIntegrity {
    private val libraryLoaded: Boolean = try {
        System.loadLibrary("antidigitalsmod")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeDigestMatches(certificateBytes: ByteArray, expectedHex: String): Boolean

    fun isTrusted(context: Context): Boolean {
        if (!libraryLoaded) return BuildConfig.DEBUG
        val expected = BuildConfig.EXPECTED_SIGNER_SHA256.trim()
        // Development APKs intentionally remain usable without the publisher's release signing key.
        if (expected.isBlank()) return BuildConfig.DEBUG
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
            }
            signatures.isNotEmpty() && signatures.any { signature ->
                nativeDigestMatches(signature.toByteArray(), expected)
            }
        } catch (_: Throwable) {
            BuildConfig.DEBUG
        }
    }
}
