package com.ignis.obsidian.util

import java.util.Locale

object MimeUtils {
    private val MIME_MAP = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "wasm" to "application/wasm",
        "md" to "text/markdown; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "pdf" to "application/pdf",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "zip" to "application/zip"
    )

    fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MIME_MAP[ext] ?: "application/octet-stream"
    }
}
