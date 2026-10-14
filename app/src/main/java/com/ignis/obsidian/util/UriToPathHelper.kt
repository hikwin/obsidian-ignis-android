package com.ignis.obsidian.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object UriToPathHelper {

    /**
     * 将 Storage Access Framework (SAF) 返回的 DocumentTree Uri 转换为真实的本地绝对文件路径
     */
    fun getPathFromTreeUri(context: Context, treeUri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        } ?: return treeUri.path

        val parts = docId.split(":")
        if (parts.size >= 2) {
            val type = parts[0]
            val relativePath = parts.drop(1).joinToString(":")
            if ("primary".equals(type, ignoreCase = true)) {
                val base = Environment.getExternalStorageDirectory().absolutePath
                return if (relativePath.isNotEmpty()) "$base/$relativePath" else base
            } else {
                // Secondary SD 卡挂载点
                val externalDirs = context.getExternalFilesDirs(null)
                for (dir in externalDirs) {
                    if (dir != null) {
                        val path = dir.absolutePath
                        val index = path.indexOf("/Android/data")
                        if (index >= 0) {
                            val root = path.substring(0, index)
                            if (root.contains(type, ignoreCase = true)) {
                                return if (relativePath.isNotEmpty()) "$root/$relativePath" else root
                            }
                        }
                    }
                }
                return "/storage/$type/$relativePath"
            }
        }
        return treeUri.path
    }
}
