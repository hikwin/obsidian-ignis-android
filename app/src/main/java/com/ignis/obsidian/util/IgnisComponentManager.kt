package com.ignis.obsidian.util

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 管理 Ignis 核心运行组件 (shim-loader.js, ignis-ui.js) 的动态下载、解压与版本支持更新
 */
class IgnisComponentManager(private val context: Context) {

    data class IgnisReleaseInfo(
        val tagName: String,
        val title: String,
        val ignisVersion: String,
        val supportedObsidianVersion: String,
        val zipDownloadUrl: String,
        val releaseNotes: String
    )

    interface UpdateCallback {
        fun onLog(message: String)
        fun onProgress(percentage: Float, downloadedBytes: Long, totalBytes: Long, speed: String)
        fun onSuccess(info: IgnisReleaseInfo)
        fun onError(error: String)
    }

    private val prefs = context.getSharedPreferences("ignis_component_config", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        const val DEFAULT_IGNIS_VERSION = "v0.8.17"
        const val DEFAULT_OBSIDIAN_VERSION = "v1.14.4"
        const val GITHUB_REPO_API = "https://api.github.com/repos/hikwin/ignis/releases/latest"
        const val GITHUB_REPO_LATEST_PAGE = "https://github.com/hikwin/ignis/releases/latest"

        // 正则提取支持的 Obsidian 版本，如 obsidian.1.14.4 -> 1.14.4
        val OBSIDIAN_VERSION_REGEX = Regex("""(?i)obsidian[.\-_](\d+(?:\.\d+)+)""")

        // 正则提取 Ignis 自身版本号，如 v0.8.17+obsidian -> 0.8.17
        val IGNIS_VERSION_REGEX = Regex("""(?i)v?(\d+\.\d+\.\d+)\+obsidian""")
        val GENERAL_SEMVER_REGEX = Regex("""(?i)v?(\d+\.\d+\.\d+)""")
    }

    fun getIgnisWebDir(): File {
        val dir = File(context.filesDir, "ignis_web")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun hasCustomShims(): Boolean {
        val dir = getIgnisWebDir()
        val shim = File(dir, "shim/shim-loader.js")
        val ui = File(dir, "ui/ignis-ui.js")
        return shim.exists() && shim.length() > 0 && ui.exists() && ui.length() > 0
    }

    fun getInstalledIgnisVersion(): String {
        return prefs.getString("installed_ignis_version", null)
            ?: if (hasCustomShims()) DEFAULT_IGNIS_VERSION else "$DEFAULT_IGNIS_VERSION (内置)"
    }

    fun getSupportedObsidianVersion(): String {
        return prefs.getString("supported_obsidian_version", null) ?: DEFAULT_OBSIDIAN_VERSION
    }

    fun getLastUpdateTime(): String {
        return prefs.getString("last_update_time", null) ?: "未更新 (内置版本)"
    }

    private fun saveUpdateInfo(ignisVer: String, obsidianVer: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timeStr = sdf.format(Date())
        prefs.edit()
            .putString("installed_ignis_version", ignisVer)
            .putString("supported_obsidian_version", obsidianVer)
            .putString("last_update_time", timeStr)
            .apply()
    }

    /**
     * 解析发布信息并提取支持的 Obsidian 版本和 Ignis 版本
     */
    fun parseReleaseMetadata(
        tagName: String,
        title: String,
        body: String,
        assetUrl: String
    ): IgnisReleaseInfo {
        // 1. 提取支持的 Obsidian 版本
        val obsMatch = OBSIDIAN_VERSION_REGEX.find(tagName)
            ?: OBSIDIAN_VERSION_REGEX.find(title)
            ?: OBSIDIAN_VERSION_REGEX.find(body)
        val rawObsVer = obsMatch?.groupValues?.get(1) ?: "1.14.4"
        val supportedObsidian = "v${rawObsVer.trim().removePrefix("v").removePrefix("V")}"

        // 2. 提取 Ignis 版本
        val ignisMatch = IGNIS_VERSION_REGEX.find(tagName)
            ?: IGNIS_VERSION_REGEX.find(title)
            ?: GENERAL_SEMVER_REGEX.find(tagName)
            ?: GENERAL_SEMVER_REGEX.find(title)
        val rawIgnisVer = ignisMatch?.groupValues?.get(1) ?: "0.8.17"
        val ignisVer = "v${rawIgnisVer.trim().removePrefix("v").removePrefix("V")}"

        return IgnisReleaseInfo(
            tagName = tagName,
            title = title,
            ignisVersion = ignisVer,
            supportedObsidianVersion = supportedObsidian,
            zipDownloadUrl = assetUrl,
            releaseNotes = body
        )
    }

    /**
     * 检查 hikwin/ignis 最新 Release 信息
     */
    suspend fun fetchLatestReleaseInfo(mirrorPrefix: String = ""): Result<IgnisReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            // 策略 1: 请求 GitHub REST API
            val request = Request.Builder()
                .url(GITHUB_REPO_API)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) OBServer/1.0")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                if (jsonStr.isNotBlank()) {
                    val root = JsonParser.parseString(jsonStr).asJsonObject
                    val tagName = root.get("tag_name")?.asString ?: ""
                    val title = root.get("name")?.asString ?: tagName
                    val body = root.get("body")?.asString ?: ""

                    var zipUrl = ""
                    val assets = root.getAsJsonArray("assets")
                    if (assets != null) {
                        for (item in assets) {
                            val assetObj = item.asJsonObject
                            val name = assetObj.get("name")?.asString ?: ""
                            if (name.equals("ignis-shims.zip", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)) {
                                zipUrl = assetObj.get("browser_download_url")?.asString ?: ""
                                break
                            }
                        }
                    }

                    if (zipUrl.isBlank() && tagName.isNotBlank()) {
                        val encodedTag = URLEncoder.encode(tagName, "UTF-8").replace("+", "%2B")
                        zipUrl = "https://github.com/hikwin/ignis/releases/download/$encodedTag/ignis-shims.zip"
                    }

                    if (tagName.isNotBlank() && zipUrl.isNotBlank()) {
                        val info = parseReleaseMetadata(tagName, title, body, zipUrl)
                        return@withContext Result.success(info)
                    }
                }
            }

            // 策略 2: API 受限或失败时，根据 GitHub releases/latest 重定向解析 Tag
            val redirectClient = OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            val pageUrl = if (mirrorPrefix.isNotBlank()) {
                val prefix = if (mirrorPrefix.endsWith("/")) mirrorPrefix else "$mirrorPrefix/"
                "$prefix$GITHUB_REPO_LATEST_PAGE"
            } else {
                GITHUB_REPO_LATEST_PAGE
            }

            val pageReq = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) OBServer/1.0")
                .build()

            val pageResp = redirectClient.newCall(pageReq).execute()
            val finalUrl = pageResp.request.url.toString()
            if (finalUrl.contains("/releases/tag/")) {
                val tag = finalUrl.substringAfter("/releases/tag/").substringBefore("?").substringBefore("/")
                val encodedTag = URLEncoder.encode(tag, "UTF-8").replace("+", "%2B")
                val zipUrl = "https://github.com/hikwin/ignis/releases/download/$encodedTag/ignis-shims.zip"
                val info = parseReleaseMetadata(tag, "Ignis Shims ($tag)", "", zipUrl)
                return@withContext Result.success(info)
            }

            Result.failure(Exception("无法获取 Release 信息，HTTP 响应码: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 下载 ignis-shims.zip，解压并提取 shim-loader.js 与 ignis-ui.js 到 filesDir/ignis_web
     */
    suspend fun downloadAndInstallShims(
        releaseInfo: IgnisReleaseInfo,
        mirrorPrefix: String = "",
        callback: UpdateCallback
    ) = withContext(Dispatchers.IO) {
        val rawUrl = releaseInfo.zipDownloadUrl
        val downloadUrl = if (mirrorPrefix.isNotBlank()) {
            val prefix = if (mirrorPrefix.endsWith("/")) mirrorPrefix else "$mirrorPrefix/"
            "$prefix$rawUrl"
        } else {
            rawUrl
        }

        val tempZipFile = File(context.cacheDir, "ignis_shims_download.zip")
        val targetDir = getIgnisWebDir()

        try {
            callback.onLog("开始请求组件压缩包: $downloadUrl")
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) OBServer/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                callback.onError("下载请求失败，HTTP 状态码: ${response.code}")
                return@withContext
            }

            val body = response.body
            if (body == null) {
                callback.onError("响应正文为空")
                return@withContext
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            callback.onLog("正在下载 ignis-shims.zip (${if (totalBytes > 0) "${totalBytes / 1024} KB" else "流式传输"})...")

            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(tempZipFile)).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 250) {
                            lastUpdate = now
                            val elapsed = maxOf(0.001, (now - startTime) / 1000.0)
                            val speedBytes = downloadedBytes / elapsed
                            val speedStr = if (speedBytes >= 1024 * 1024) {
                                String.format("%.2f MB/s", speedBytes / (1024 * 1024))
                            } else {
                                String.format("%.1f KB/s", speedBytes / 1024)
                            }
                            val percentage = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes * 100f) else 0f
                            callback.onProgress(percentage, downloadedBytes, totalBytes, speedStr)
                        }
                    }
                }
            }

            callback.onProgress(100f, downloadedBytes, downloadedBytes, "下载完成")
            callback.onLog("下载完成，开始解压与部署组件...")

            // 解压 ZIP 文件
            val shimDir = File(targetDir, "shim")
            val uiDir = File(targetDir, "ui")
            if (!shimDir.exists()) shimDir.mkdirs()
            if (!uiDir.exists()) uiDir.mkdirs()

            var extractedShim = false
            var extractedUi = false

            ZipInputStream(FileInputStream(tempZipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory) {
                        val outFile = when {
                            name.endsWith("shim-loader.js") -> {
                                extractedShim = true
                                File(shimDir, "shim-loader.js")
                            }
                            name.endsWith("ignis-ui.js") -> {
                                extractedUi = true
                                File(uiDir, "ignis-ui.js")
                            }
                            else -> null
                        }

                        if (outFile != null) {
                            callback.onLog("提取文件: $name -> ${outFile.name}")
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val finalShim = File(shimDir, "shim-loader.js")
            val finalUi = File(uiDir, "ignis-ui.js")

            if (extractedShim && extractedUi && finalShim.exists() && finalShim.length() > 0 && finalUi.exists() && finalUi.length() > 0) {
                callback.onLog("正在应用 Android 环境专属优化补丁 (UTF-8、Terms 握手、路由适配)...")
                applyPatches(finalShim, finalUi)

                callback.onLog("组件校验成功！")
                callback.onLog("• shim-loader.js: ${finalShim.length() / 1024} KB")
                callback.onLog("• ignis-ui.js: ${finalUi.length() / 1024} KB")
                callback.onLog("• Ignis 版本: ${releaseInfo.ignisVersion}")
                callback.onLog("• 适配 Obsidian 核心版本: ${releaseInfo.supportedObsidianVersion}")

                saveUpdateInfo(releaseInfo.ignisVersion, releaseInfo.supportedObsidianVersion)
                callback.onSuccess(releaseInfo)
            } else {
                callback.onError("解压校验失败：未在 zip 中找到有效的 shim-loader.js 或 ignis-ui.js")
            }

        } catch (e: Exception) {
            callback.onError("更新异常: ${e.message}")
            e.printStackTrace()
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }
        }
    }

    /**
     * 自动对新下载的 shim-loader.js 与 ignis-ui.js 打入 Android 原生运行所需的所有补丁
     */
    private fun applyPatches(shimFile: File, uiFile: File) {
        val buildVaultUrlCode = """
  function buildVaultUrl(vaultId) {
    var base = window.__IGNIS_APP_BASE__ || "";
    var mode = window.__IGNIS_URL_MODE__ || "restful";
    var prefix = mode === "traditional" ? "/index.php" : "/";
    return base + prefix + "?vault=" + encodeURIComponent(vaultId);
  }
"""
        // 1. 补丁 uiFile
        if (uiFile.exists()) {
            var uiText = uiFile.readText(Charsets.UTF_8)
            if (!uiText.contains("function buildVaultUrl")) {
                uiText = uiText.replace("(() => {", "(() => {$buildVaultUrlCode")
            }
            uiText = uiText.replace(
                "history.replaceState(null, \"\", \"/?vault=\" + encodeURIComponent(newName));",
                "history.replaceState(null, \"\", buildVaultUrl(newName));"
            )
            uiText = uiText.replace(
                "target.location.href = \"/?vault=\" + encodeURIComponent(id);",
                "target.location.href = buildVaultUrl(id);"
            )
            uiFile.writeText(uiText, Charsets.UTF_8)
        }

        // 2. 补丁 shimFile
        if (shimFile.exists()) {
            var shimText = shimFile.readText(Charsets.UTF_8)
            if (!shimText.contains("function buildVaultUrl")) {
                shimText = shimText.replace("(() => {", "(() => {$buildVaultUrlCode")
            }
            shimText = shimText.replace(
                "history.replaceState(null, \"\", \"/?vault=\" + encodeURIComponent(newName));",
                "history.replaceState(null, \"\", buildVaultUrl(newName));"
            )
            shimText = shimText.replace(
                "target.location.href = \"/?vault=\" + encodeURIComponent(id);",
                "target.location.href = buildVaultUrl(id);"
            )

            // Terms 握手补丁，防止 Obsidian 1.14+ 握手失败调用 window.close() 导致白屏
            val newTerms = """    terms: () => {
      const fallbackTerms = "I understand and agree that I am not allowed to distribute the Obsidian application, in any form, without explicit approval from the Obsidian team. I also understand that Obsidian is a registered trademark, and I cannot use it without explicit permission granted by the Obsidian team.";
      return window.__obsidianTerms || fallbackTerms;
    },"""
            val oldTermsPattern = """    terms: () => {
      if (!window.__obsidianTerms) {
        console.error(
          "[ignis] Obsidian asked for its terms handshake but the server extracted no value from this Obsidian version. Set OBSIDIAN_TERMS_STRING, or the terms extraction needs updating."
        );
      }
      return window.__obsidianTerms || null;
    },"""

            if (shimText.contains(oldTermsPattern)) {
                shimText = shimText.replace(oldTermsPattern, newTerms)
            } else if (shimText.contains("terms: () =>")) {
                val tIdx = shimText.indexOf("terms: () =>")
                val endTIdx = shimText.indexOf("},", tIdx)
                if (endTIdx != -1) {
                    shimText = shimText.substring(0, tIdx) + newTerms.trimStart() + shimText.substring(endTIdx + 2)
                }
            }

            // resync 导出补丁
            shimText = shimText.replace(
                "setTreeEtag\n    };",
                "setTreeEtag,\n      resync\n    };"
            )

            // UTF-8 编码补丁
            shimText = shimText.replace("\"Content-Type\": \"application/json\"", "\"Content-Type\": \"application/json; charset=utf-8\"")
            shimText = shimText.replace("\"Content-Type\", \"application/json\"", "\"Content-Type\", \"application/json; charset=utf-8\"")

            shimFile.writeText(shimText, Charsets.UTF_8)
        }
    }
}
