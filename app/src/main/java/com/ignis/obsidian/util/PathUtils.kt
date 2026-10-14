package com.ignis.obsidian.util

import java.io.File
import java.net.URLDecoder

object PathUtils {
    /**
     * 将相对路径解析为在根目录下的安全绝对路径，防止路径遍历攻击。
     */
    fun resolveVaultPath(vaultRoot: File, relativePath: String): File? {
        // 1. 解码 URL 编码字符（如中文文件名、空格 %20 等）
        val decodedRel = try {
            if (relativePath.contains('%')) URLDecoder.decode(relativePath, "UTF-8") else relativePath
        } catch (_: Exception) {
            relativePath
        }

        val cleanRel = decodedRel.trim().replace('\\', '/').trimStart('/')

        val rootCanonical = try {
            vaultRoot.canonicalFile
        } catch (_: Exception) {
            vaultRoot.absoluteFile
        }

        val target = try {
            File(rootCanonical, cleanRel).canonicalFile
        } catch (_: Exception) {
            File(rootCanonical, cleanRel).absoluteFile
        }

        val rootPath = rootCanonical.absolutePath.trimEnd(File.separatorChar) + File.separatorChar
        val targetPath = target.absolutePath

        // 允许根目录本身或者其内部的子文件/子目录
        if (targetPath != rootCanonical.absolutePath && !targetPath.startsWith(rootPath)) {
            // Path traversal rejected
            return null
        }
        return target
    }

    /**
     * 如果文件不存在，检测是否加上 .md 后存在
     */
    fun resolveWithMdFallback(target: File): File {
        if (!target.exists()) {
            val mdFile = File(target.parentFile, "${target.name}.md")
            if (mdFile.exists()) {
                return mdFile
            }
        }
        return target
    }
}
