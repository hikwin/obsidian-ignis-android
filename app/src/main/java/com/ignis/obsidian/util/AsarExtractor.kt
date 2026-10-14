package com.ignis.obsidian.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

object AsarExtractor {

    interface ExtractListener {
        fun onProgress(extractedFiles: Int, totalFiles: Int, currentFile: String)
        fun onLog(message: String)
    }

    /**
     * 将 .asar.gz 文件解压并提取到目标目录
     */
    fun extractGzAsar(
        gzFile: File,
        targetDir: File,
        tempDir: File,
        listener: ExtractListener? = null
    ): Boolean {
        if (!gzFile.exists()) {
            listener?.onLog("错误：gz 文件不存在: ${gzFile.absolutePath}")
            return false
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val tempAsar = File(tempDir, "temp_obsidian_${System.currentTimeMillis()}.asar")
        try {
            listener?.onLog("正在解压 gzip 归档...")
            GZIPInputStream(BufferedInputStream(FileInputStream(gzFile))).use { gzis ->
                BufferedOutputStream(FileOutputStream(tempAsar)).use { fos ->
                    val buffer = ByteArray(64 * 1024)
                    var len: Int
                    while (gzis.read(buffer).also { len = it } != -1) {
                        fos.write(buffer, 0, len)
                    }
                }
            }
            listener?.onLog("Gzip 解压完成，开始提取 ASAR 文件...")
            val success = extractAsar(tempAsar, targetDir, listener)
            return success
        } catch (e: Exception) {
            listener?.onLog("解压 ASAR 发生异常: ${e.message}")
            e.printStackTrace()
            return false
        } finally {
            if (tempAsar.exists()) {
                tempAsar.delete()
            }
        }
    }

    /**
     * 从标准 asar 归档中提取文件
     */
    fun extractAsar(
        asarFile: File,
        targetDir: File,
        listener: ExtractListener? = null
    ): Boolean {
        RandomAccessFile(asarFile, "r").use { raf ->
            val headerBytes = ByteArray(16)
            raf.readFully(headerBytes)

            val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.int // magic
            val headerSize = bb.int.toLong() and 0xFFFFFFFFL
            bb.int // headerSize - 4
            val jsonLen = bb.int.toLong() and 0xFFFFFFFFL

            if (jsonLen <= 0 || jsonLen > 50 * 1024 * 1024) {
                listener?.onLog("ASAR 头部格式错误，jsonLen: $jsonLen")
                return false
            }

            val jsonBytes = ByteArray(jsonLen.toInt())
            raf.readFully(jsonBytes)
            val jsonStr = String(jsonBytes, Charsets.UTF_8)

            val jsonObject = JsonParser.parseString(jsonStr).asJsonObject
            val baseOffset = 8 + headerSize

            val totalFiles = countFiles(jsonObject)
            listener?.onLog("ASAR 包含 $totalFiles 个文件，开始提取...")

            var extracted = 0
            walkAndExtract(raf, jsonObject, targetDir, baseOffset, listener, totalFiles, object : Counter {
                override var count: Int
                    get() = extracted
                    set(value) { extracted = value }
            })

            listener?.onLog("提取完成，共提取 $extracted 个文件！")
            return true
        }
    }

    private interface Counter {
        var count: Int
    }

    private fun countFiles(tree: JsonObject): Int {
        var count = 0
        val filesObj = tree.getAsJsonObject("files") ?: return 0
        for ((_, entryEl) in filesObj.entrySet()) {
            val entry = entryEl.asJsonObject
            if (entry.has("files")) {
                count += countFiles(entry)
            } else if (entry.has("size")) {
                count++
            }
        }
        return count
    }

    private fun walkAndExtract(
        raf: RandomAccessFile,
        tree: JsonObject,
        currentDir: File,
        baseOffset: Long,
        listener: ExtractListener?,
        totalFiles: Int,
        counter: Counter
    ) {
        val filesObj = tree.getAsJsonObject("files") ?: return
        for ((name, entryEl) in filesObj.entrySet()) {
            val entry = entryEl.asJsonObject
            val targetFile = File(currentDir, name)

            if (entry.has("files")) {
                if (!targetFile.exists()) {
                    targetFile.mkdirs()
                }
                walkAndExtract(raf, entry, targetFile, baseOffset, listener, totalFiles, counter)
            } else if (entry.has("size")) {
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                val fileSize = entry.get("size").asLong
                val offsetEl = entry.get("offset")
                val offsetStr = offsetEl?.asString ?: "0"
                val fileOffset = baseOffset + offsetStr.toLong()

                if (fileSize > 0) {
                    raf.seek(fileOffset)
                    BufferedOutputStream(FileOutputStream(targetFile)).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = fileSize
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read == -1) break
                            fos.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                } else {
                    targetFile.createNewFile()
                }

                counter.count++
                listener?.onProgress(counter.count, totalFiles, name)
            }
        }
    }
}
