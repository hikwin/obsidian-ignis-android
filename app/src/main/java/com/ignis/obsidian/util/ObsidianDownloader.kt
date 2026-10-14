package com.ignis.obsidian.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ObsidianDownloader(private val context: Context) {

    interface DownloadCallback {
        fun onProgress(percentage: Float, downloadedBytes: Long, totalBytes: Long, speed: String)
        fun onExtractProgress(extracted: Int, total: Int, currentFile: String)
        fun onLog(message: String)
        fun onSuccess(version: String)
        fun onError(error: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val prefs = context.getSharedPreferences("obsidian_config", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_VERSION = "v1.14.4"
        val PRESET_VERSIONS = listOf("v1.14.4", "v1.12.7", "v1.12.4", "v1.11.7", "v1.10.6")

        // 镜像加速源前缀
        val MIRROR_PRESETS = listOf(
            "官方直接连接" to "",
            "ghfast 镜像源" to "https://ghfast.top/",
            "ghproxy 镜像源" to "https://ghproxy.net/"
        )
    }

    /**
     * 获取可供下载的 Obsidian 版本列表（优先置顶 Ignis 最新支持的适配版本）
     */
    fun getAvailableVersions(): List<String> {
        val ignisMgr = IgnisComponentManager(context)
        val supportedVer = ignisMgr.getSupportedObsidianVersion()
        val list = mutableListOf<String>()
        if (supportedVer.isNotBlank()) {
            list.add(supportedVer)
        }
        for (v in PRESET_VERSIONS) {
            if (!list.contains(v)) {
                list.add(v)
            }
        }
        return list
    }

    fun getObsidianDir(): File {
        val dir = File(context.filesDir, "obsidian_assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getTempDir(): File {
        val dir = File(context.cacheDir, "obsidian_temp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isInstalled(): Boolean {
        val dir = getObsidianDir()
        val appJs = File(dir, "app.js")
        val appCss = File(dir, "app.css")
        return appJs.exists() && appJs.length() > 0 && appCss.exists() && appCss.length() > 0
    }

    fun getInstalledVersion(): String {
        return prefs.getString("installed_version", null) ?: if (isInstalled()) "v1.14.4" else "未安装"
    }

    private fun setInstalledVersion(ver: String) {
        prefs.edit().putString("installed_version", ver).apply()
    }

    suspend fun downloadAndInstall(
        version: String = DEFAULT_VERSION,
        mirrorPrefix: String = "",
        callback: DownloadCallback
    ) = withContext(Dispatchers.IO) {
        val cleanVer = version.trim().substringBefore(" ").removePrefix("v").removePrefix("V").trim()
        val formattedVer = "v$cleanVer"
        val filename = "obsidian-$cleanVer.asar.gz"
        val rawUrl = "https://github.com/obsidianmd/obsidian-releases/releases/download/$formattedVer/$filename"
        val downloadUrl = if (mirrorPrefix.isNotBlank()) {
            val prefix = if (mirrorPrefix.endsWith("/")) mirrorPrefix else "$mirrorPrefix/"
            "$prefix$rawUrl"
        } else {
            rawUrl
        }

        val targetDir = getObsidianDir()
        val tempDir = getTempDir()
        val destGzFile = File(tempDir, filename)

        try {
            callback.onLog("开始请求下载: $downloadUrl")
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) ObsidianIgnis/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = "下载请求失败，HTTP 状态码: ${response.code}"
                callback.onError(errorMsg)
                callback.onLog(errorMsg)
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

            callback.onLog("正在下载核心文件（总大小: ${totalBytes / 1024 / 1024} MB）...")

            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(destGzFile)).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 300) {
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
            callback.onLog("下载完成，开始解压与部署...")

            // 解压与提取
            val extractSuccess = AsarExtractor.extractGzAsar(
                destGzFile,
                targetDir,
                tempDir,
                object : AsarExtractor.ExtractListener {
                    override fun onProgress(extractedFiles: Int, totalFiles: Int, currentFile: String) {
                        callback.onExtractProgress(extractedFiles, totalFiles, currentFile)
                    }

                    override fun onLog(message: String) {
                        callback.onLog(message)
                    }
                }
            )

            if (extractSuccess && isInstalled()) {
                val appJs = File(targetDir, "app.js")
                val appCss = File(targetDir, "app.css")
                val indexHtml = File(targetDir, "index.html")
                callback.onLog("核心校验成功: app.js (${appJs.length() / 1024} KB), app.css (${appCss.length() / 1024} KB), index.html 存在: ${indexHtml.exists()}")
                setInstalledVersion(formattedVer)
                callback.onLog("部署成功！Obsidian 核心版本: $formattedVer")
                callback.onSuccess(formattedVer)
            } else {
                callback.onError("核心文件解压后完整性校验失败，请重试")
            }

        } catch (e: Exception) {
            callback.onError("发生异常: ${e.message}")
            e.printStackTrace()
        } finally {
            if (destGzFile.exists()) {
                destGzFile.delete()
            }
        }
    }
}
