package com.digitalsmod.digitalliquid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

/** Identifies user-selected background media without decoding large files on the UI thread. */
object PanelBackgroundMedia {
    private val videoExtensions = setOf(
        "mp4", "m4v", "mov", "3gp", "3gpp", "3g2", "mkv", "webm", "avi", "ts",
        "m2ts", "mts", "mpeg", "mpg", "mpe", "vob", "wmv", "flv", "f4v", "ogv",
        "m2v", "hevc", "h264", "h265"
    )
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "tif", "tiff"
    )

    /** Returns "video", "image", or null for unsupported/unknown content. */
    fun kind(context: Context, uri: Uri): String? {
        val mime = try { context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT) } catch (_: Exception) { "" }
        if (mime.startsWith("video/")) return "video"
        if (mime.startsWith("image/")) return "image"

        val name = queryDisplayName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            in videoExtensions -> "video"
            in imageExtensions -> "image"
            else -> null
        }
    }

    fun displayName(context: Context, uri: Uri): String = queryDisplayName(context, uri)

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) cursor.getString(column).orEmpty() else ""
                } else ""
            }.orEmpty()
        } catch (_: Exception) { "" }
    }
}
