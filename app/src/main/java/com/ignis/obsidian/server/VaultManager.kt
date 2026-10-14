package com.ignis.obsidian.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class VaultManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("vault_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getVaultsRootDir(): File {
        val external = context.getExternalFilesDir("vaults")
        val dir = external ?: File(context.filesDir, "vaults")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    init {
        ensureDefaultVault()
    }

    private fun ensureDefaultVault() {
        val root = getVaultsRootDir()
        val defaultVault = File(root, "DefaultVault")
        if (!defaultVault.exists()) {
            defaultVault.mkdirs()
            val obsidianDir = File(defaultVault, ".obsidian")
            obsidianDir.mkdirs()

            // 创建示例欢迎笔记
            val welcomeFile = File(defaultVault, "欢迎使用 OBServer.md")
            if (!welcomeFile.exists()) {
                val welcomeContent = """
                    # 🎉 欢迎使用 OBServer！
                    
                    这是一个运行在 Android 上的独立 Obsidian Web 系统。
                    
                    ## ✨ 特性说明
                    - 🚀 **原生后端引擎**：完全使用 Android 本地服务替代 PHP，性能强劲、低功耗。
                    - 📱 **随时随地访问**：支持本 App 内置全屏浏览，也可以在局域网中的电脑、平板上输入手机 IP 直接访问。
                    - 📂 **离线与独立**：所有的 Markdown 笔记和配置都保存在本机的专属存储目录中。
                    - 🔄 **官方资源**：完全兼容 Obsidian 官方最新核心与 Markdown 生态。
                    
                    祝您记录愉快！
                """.trimIndent()
                welcomeFile.writeText(welcomeContent, Charsets.UTF_8)
            }
        }
    }

    fun getExternalVaults(): List<VaultItem> {
        val json = prefs.getString("external_vaults_list", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<VaultItem>>() {}.type
            gson.fromJson<List<VaultItem>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveExternalVaults(list: List<VaultItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString("external_vaults_list", json).apply()
    }

    fun addExternalVault(name: String, directory: File): Boolean {
        if (!directory.exists() || !directory.isDirectory) {
            return false
        }
        val cleanName = name.trim().ifBlank { directory.name }

        // 确保 .obsidian 目录存在
        val obsDir = File(directory, ".obsidian")
        if (!obsDir.exists()) {
            try {
                obsDir.mkdirs()
            } catch (_: Exception) {}
        }

        val externals = getExternalVaults().toMutableList()
        // 避免重复路径或同名
        externals.removeAll {
            try {
                File(it.path).canonicalPath == directory.canonicalPath || it.id == cleanName
            } catch (_: Exception) {
                it.path == directory.absolutePath || it.id == cleanName
            }
        }

        val newVault = VaultItem(
            id = cleanName,
            name = cleanName,
            path = directory.absolutePath,
            isExternal = true
        )
        externals.add(newVault)
        saveExternalVaults(externals)
        return true
    }

    fun getDefaultVaultId(): String {
        val saved = prefs.getString("current_vault_id", null)
        if (saved != null && getVaultPath(saved) != null) {
            return saved
        }
        val firstVault = listVaults().firstOrNull()?.id ?: "DefaultVault"
        setCurrentVaultId(firstVault)
        return firstVault
    }

    fun setCurrentVaultId(id: String) {
        prefs.edit().putString("current_vault_id", id).apply()
    }

    fun getVaultPath(vaultId: String): File? {
        // 1. 优先从已添加的本地外部目录库中查找
        val external = getExternalVaults().firstOrNull { it.id == vaultId || it.name == vaultId }
        if (external != null) {
            val dir = File(external.path)
            if (dir.exists() && dir.isDirectory) {
                return dir
            }
        }

        // 2. 从内置标准知识库目录中查找
        val root = getVaultsRootDir()
        val vaultDir = File(root, vaultId)
        if (vaultDir.exists() && vaultDir.isDirectory) {
            return vaultDir
        }

        // 3. 回退：检查 vaultId 是否本身就是一个存在的绝对目录路径
        val directDir = File(vaultId)
        if (directDir.exists() && directDir.isDirectory) {
            return directDir
        }

        return null
    }

    fun getHiddenInternalVaultIds(): Set<String> {
        return prefs.getStringSet("hidden_internal_vaults", emptySet()) ?: emptySet()
    }

    private fun saveHiddenInternalVaultIds(ids: Set<String>) {
        prefs.edit().putStringSet("hidden_internal_vaults", ids).apply()
    }

    /**
     * 仅移除知识库引用/关联，不删除任何磁盘文件。
     * - 外部库：从 external_vaults_list 中移除条目
     * - 内置库：加入 hidden_internal_vaults 黑名单，下次扫描时过滤
     * - DefaultVault 不可移除，返回 false
     */
    fun removeVaultReference(vaultId: String): Boolean {
        if (vaultId == "DefaultVault") return false

        // 1. 外部关联库 — 仅移除配置记录
        val externals = getExternalVaults().toMutableList()
        val extItem = externals.firstOrNull { it.id == vaultId }
        if (extItem != null) {
            externals.remove(extItem)
            saveExternalVaults(externals)
            if (getDefaultVaultId() == vaultId) {
                val next = listVaults().firstOrNull { it.id != vaultId }?.id ?: "DefaultVault"
                setCurrentVaultId(next)
            }
            return true
        }

        // 2. 内置库 — 写入黑名单，不物理删除文件
        val hidden = getHiddenInternalVaultIds().toMutableSet()
        hidden.add(vaultId)
        saveHiddenInternalVaultIds(hidden)
        if (getDefaultVaultId() == vaultId) {
            val next = listVaults().firstOrNull { it.id != vaultId }?.id ?: "DefaultVault"
            setCurrentVaultId(next)
        }
        return true
    }

    fun listVaults(): List<VaultItem> {
        val hiddenIds = getHiddenInternalVaultIds()
        val list = mutableListOf<VaultItem>()

        // 1. 内置存储知识库（过滤黑名单）
        val root = getVaultsRootDir()
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") } ?: emptyArray()
        for (dir in dirs) {
            if (dir.name in hiddenIds) continue
            list.add(
                VaultItem(
                    id = dir.name,
                    name = dir.name,
                    path = dir.absolutePath,
                    isExternal = false
                )
            )
        }

        // 2. 外部添加的手机本地知识库
        val externals = getExternalVaults()
        for (item in externals) {
            val dir = File(item.path)
            if (dir.exists() && dir.isDirectory) {
                if (list.none { it.id == item.id }) {
                    list.add(item)
                }
            }
        }

        return list
    }

    fun createVault(name: String): Boolean {
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.contains("/") || cleanName.contains("\\") || cleanName.startsWith(".")) {
            return false
        }
        val root = getVaultsRootDir()
        val target = File(root, cleanName)
        if (target.exists()) return false

        if (target.mkdirs()) {
            val obs = File(target, ".obsidian")
            obs.mkdirs()
            return true
        }
        return false
    }

    fun renameVault(oldId: String, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isBlank() || cleanName.contains("/") || cleanName.contains("\\") || cleanName.startsWith(".")) {
            return false
        }

        // 检查是否为外部库
        val externals = getExternalVaults().toMutableList()
        val extIndex = externals.indexOfFirst { it.id == oldId }
        if (extIndex >= 0) {
            val item = externals[extIndex]
            externals[extIndex] = item.copy(id = cleanName, name = cleanName)
            saveExternalVaults(externals)
            if (getDefaultVaultId() == oldId) {
                setCurrentVaultId(cleanName)
            }
            return true
        }

        // 内置库重命名
        val root = getVaultsRootDir()
        val src = File(root, oldId)
        val dst = File(root, cleanName)
        if (!src.exists() || dst.exists()) return false

        val success = src.renameTo(dst)
        if (success && getDefaultVaultId() == oldId) {
            setCurrentVaultId(cleanName)
        }
        return success
    }

    fun removeVault(vaultId: String): Boolean {
        // 如果是外部关联知识库，仅移除关联配置，不删除用户手机上的原文件
        val externals = getExternalVaults().toMutableList()
        val extItem = externals.firstOrNull { it.id == vaultId }
        if (extItem != null) {
            externals.remove(extItem)
            saveExternalVaults(externals)
            if (getDefaultVaultId() == vaultId) {
                val next = listVaults().firstOrNull()?.id ?: "DefaultVault"
                setCurrentVaultId(next)
            }
            return true
        }

        // 内置库则直接物理删除
        val root = getVaultsRootDir()
        val target = File(root, vaultId)
        if (!target.exists()) return false
        val success = target.deleteRecursively()
        if (getDefaultVaultId() == vaultId) {
            val next = listVaults().firstOrNull()?.id ?: "DefaultVault"
            setCurrentVaultId(next)
        }
        return success
    }

    data class VaultItem(
        val id: String,
        val name: String,
        val path: String,
        val isExternal: Boolean = false
    )
}
