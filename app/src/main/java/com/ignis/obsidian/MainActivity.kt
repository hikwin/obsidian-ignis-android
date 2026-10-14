package com.ignis.obsidian

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ignis.obsidian.databinding.ActivityMainBinding
import com.ignis.obsidian.databinding.DialogCreateVaultBinding
import com.ignis.obsidian.databinding.DialogDownloadObsidianBinding
import com.ignis.obsidian.databinding.DialogOpenLocalVaultBinding
import com.ignis.obsidian.databinding.DialogUpdateIgnisBinding
import com.ignis.obsidian.server.IgnisServerService
import com.ignis.obsidian.server.VaultManager
import com.ignis.obsidian.ui.ObsidianWebViewActivity
import com.ignis.obsidian.util.IgnisComponentManager
import com.ignis.obsidian.util.NetworkUtils
import com.ignis.obsidian.util.ObsidianDownloader
import com.ignis.obsidian.util.UriToPathHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloader: ObsidianDownloader
    private lateinit var vaultManager: VaultManager
    private lateinit var ignisManager: IgnisComponentManager
    private var pendingVaultLoad: Pair<String, String>? = null

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val requestLegacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
                perms[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        if (granted) {
            val pending = pendingVaultLoad
            if (pending != null) {
                pendingVaultLoad = null
                onLocalPathSelected(pending.first, pending.second)
            }
        } else {
            Toast.makeText(this, "未授予存储权限，无法加载外部知识库中的笔记", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isExternalStorageAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val resolvedPath = UriToPathHelper.getPathFromTreeUri(this, uri)
            if (!resolvedPath.isNullOrBlank()) {
                onLocalPathSelected(resolvedPath)
            } else {
                Toast.makeText(this, "未能解析文件夹路径: $uri", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloader = ObsidianDownloader(this)
        vaultManager = VaultManager(this)
        ignisManager = IgnisComponentManager(this)

        checkPermissions()
        initHeaderControls()
        initServerControls()
        initCoreControls()
        initIgnisControls()
        initVaultControls()
        observeServerState()
    }

    override fun onResume() {
        super.onResume()
        updateServerUi(IgnisServerService.isRunning, IgnisServerService.currentUrl)
        updateCoreStatus()
        updateIgnisStatus()
        refreshVaultList()

        // 检查用户从系统设置授权后返回的情况
        val pending = pendingVaultLoad
        if (pending != null && isExternalStorageAccessGranted()) {
            pendingVaultLoad = null
            onLocalPathSelected(pending.first, pending.second)
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestLegacyStorageLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    private fun initHeaderControls() {
        binding.btnGithub.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hikwin/obsidian-ignis-android"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initServerControls() {
        val prefs = getSharedPreferences("ignis_settings", Context.MODE_PRIVATE)
        val savedPort = prefs.getInt("server_port", 8080)
        val savedAccessCode = prefs.getString("server_access_code", "") ?: ""
        binding.etPort.setText(savedPort.toString())
        binding.etAccessCode.setText(savedAccessCode)

        binding.btnToggleServer.setOnClickListener {
            if (IgnisServerService.isRunning) {
                IgnisServerService.stopService(this)
            } else {
                val portStr = binding.etPort.text.toString().trim()
                val port = portStr.toIntOrNull() ?: 8080
                if (port !in 1024..65535) {
                    Toast.makeText(this, "请输入合法的端口号 (1024 ~ 65535)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val accessCode = binding.etAccessCode.text.toString().trim()
                prefs.edit()
                    .putInt("server_port", port)
                    .putString("server_access_code", accessCode)
                    .apply()

                IgnisServerService.startService(this, port, accessCode)
            }
        }

        binding.btnOpenAppWebview.setOnClickListener {
            if (!downloader.isInstalled()) {
                showDownloadGuideDialog()
                return@setOnClickListener
            }

            val portStr = binding.etPort.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 8080
            val accessCode = binding.etAccessCode.text.toString().trim()
            prefs.edit()
                .putInt("server_port", port)
                .putString("server_access_code", accessCode)
                .apply()

            if (!IgnisServerService.isRunning) {
                IgnisServerService.startService(this, port, accessCode)
            }

            val targetUrl = getActiveUrl()
            val intent = Intent(this, ObsidianWebViewActivity::class.java).apply {
                putExtra("target_url", targetUrl)
                putExtra("access_code", accessCode)
            }
            startActivity(intent)
        }

        binding.btnOpenExternalBrowser.setOnClickListener {
            val url = getActiveUrl()
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开外部浏览器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyLanUrl.setOnClickListener {
            val url = getActiveLanUrl()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Obsidian URL", url))
            Toast.makeText(this, "已复制局域网访问地址到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getActivePort(): Int {
        return binding.etPort.text.toString().trim().toIntOrNull() ?: 8080
    }

    private fun getActiveUrl(): String {
        val port = getActivePort()
        val vault = try {
            java.net.URLEncoder.encode(vaultManager.getDefaultVaultId(), "UTF-8")
        } catch (_: Exception) {
            vaultManager.getDefaultVaultId()
        }
        return "http://127.0.0.1:$port/?vault=$vault"
    }

    private fun getActiveLanUrl(): String {
        val port = getActivePort()
        val ip = NetworkUtils.getLocalIpAddress()
        val vault = try {
            java.net.URLEncoder.encode(vaultManager.getDefaultVaultId(), "UTF-8")
        } catch (_: Exception) {
            vaultManager.getDefaultVaultId()
        }
        return "http://$ip:$port/?vault=$vault"
    }

    private fun observeServerState() {
        IgnisServerService.onStateChangeListener = { running, url ->
            runOnUiThread {
                updateServerUi(running, url)
            }
        }
    }

    private fun updateServerUi(running: Boolean, url: String = "") {
        val lanUrl = if (url.isNotBlank()) url else getActiveLanUrl()

        binding.tvLanUrl.text = lanUrl

        if (running) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_running)
            binding.tvServerStatus.text = getString(R.string.server_running)
            binding.tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnToggleServer.text = getString(R.string.btn_stop_server)
            binding.btnToggleServer.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            binding.etPort.isEnabled = false
            binding.etAccessCode.isEnabled = false
        } else {
            binding.viewStatusDot.setBackgroundResource(R.drawable.shape_dot_stopped)
            binding.tvServerStatus.text = getString(R.string.server_stopped)
            binding.tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.obsidian_text))
            binding.btnToggleServer.text = getString(R.string.btn_start_server)
            binding.btnToggleServer.setBackgroundColor(ContextCompat.getColor(this, R.color.obsidian_primary))
            binding.etPort.isEnabled = true
            binding.etAccessCode.isEnabled = true
        }
    }

    private fun initCoreControls() {
        updateCoreStatus()
        binding.btnDownloadCore.setOnClickListener {
            showDownloadCoreDialog()
        }
    }

    private fun updateCoreStatus() {
        val isInstalled = downloader.isInstalled()
        val version = downloader.getInstalledVersion()
        if (isInstalled) {
            binding.tvCoreVersion.text = "已安装 ($version)"
            binding.tvCoreVersion.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvCoreVersion.text = "未安装（请先下载）"
            binding.tvCoreVersion.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
        binding.tvCorePath.text = "存储路径: ${downloader.getObsidianDir().absolutePath}"
    }

    private fun initIgnisControls() {
        updateIgnisStatus()
        binding.btnUpdateIgnisShims.setOnClickListener {
            showUpdateIgnisDialog()
        }
    }

    private fun updateIgnisStatus() {
        binding.tvIgnisVersion.text = ignisManager.getInstalledIgnisVersion()
        binding.tvIgnisSupportedObsidian.text = ignisManager.getSupportedObsidianVersion()
    }

    private fun showUpdateIgnisDialog() {
        val dialogBinding = DialogUpdateIgnisBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.tvCurrentIgnisStatus.text = "当前组件版本: ${ignisManager.getInstalledIgnisVersion()}"
        dialogBinding.tvCurrentObsidianSupport.text = "支持 Obsidian: ${ignisManager.getSupportedObsidianVersion()}"

        val mirrors = ObsidianDownloader.MIRROR_PRESETS.map { it.first }
        val mirrorAdapter = createObsidianSpinnerAdapter(mirrors)
        dialogBinding.spMirror.adapter = mirrorAdapter

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnStartUpdate.setOnClickListener {
            val mirrorPrefix = ObsidianDownloader.MIRROR_PRESETS[dialogBinding.spMirror.selectedItemPosition].second

            dialogBinding.btnStartUpdate.isEnabled = false
            dialogBinding.btnCancel.isEnabled = false
            dialogBinding.spMirror.isEnabled = false
            dialogBinding.layoutProgress.visibility = View.VISIBLE
            dialogBinding.tvProgressStatus.text = "正在检查最新组件版本..."
            dialogBinding.tvLog.text = ""

            lifecycleScope.launch {
                dialogBinding.tvLog.append("正在连接 GitHub 获取最新 Release (hikwin/ignis)...\n")
                val releaseResult = ignisManager.fetchLatestReleaseInfo(mirrorPrefix)
                if (releaseResult.isFailure) {
                    val errMsg = releaseResult.exceptionOrNull()?.message ?: "获取 Release 失败"
                    runOnUiThread {
                        dialogBinding.tvProgressStatus.text = "检查失败"
                        dialogBinding.tvLog.append("错误: $errMsg\n")
                        Toast.makeText(this@MainActivity, "检查最新版本失败: $errMsg", Toast.LENGTH_LONG).show()
                        dialogBinding.btnStartUpdate.isEnabled = true
                        dialogBinding.btnCancel.isEnabled = true
                        dialogBinding.spMirror.isEnabled = true
                    }
                    return@launch
                }

                val releaseInfo = releaseResult.getOrThrow()
                runOnUiThread {
                    dialogBinding.tvLog.append("成功检测到 Release: ${releaseInfo.tagName}\n")
                    dialogBinding.tvLog.append("• Ignis 版本: ${releaseInfo.ignisVersion}\n")
                    dialogBinding.tvLog.append("• 正则识别支持 Obsidian: ${releaseInfo.supportedObsidianVersion}\n")
                    dialogBinding.tvProgressStatus.text = "正在下载并部署组件..."
                }

                ignisManager.downloadAndInstallShims(
                    releaseInfo = releaseInfo,
                    mirrorPrefix = mirrorPrefix,
                    callback = object : IgnisComponentManager.UpdateCallback {
                        override fun onProgress(
                            percentage: Float,
                            downloadedBytes: Long,
                            totalBytes: Long,
                            speed: String
                        ) {
                            runOnUiThread {
                                dialogBinding.pbDownload.progress = percentage.toInt()
                                val kbDone = downloadedBytes / 1024
                                val kbTotal = if (totalBytes > 0) "${totalBytes / 1024} KB" else "?"
                                dialogBinding.tvProgressStatus.text = "下载中: $kbDone KB / $kbTotal @ $speed (${percentage.toInt()}%)"
                            }
                        }

                        override fun onLog(message: String) {
                            runOnUiThread {
                                dialogBinding.tvLog.append("$message\n")
                            }
                        }

                        override fun onSuccess(info: IgnisComponentManager.IgnisReleaseInfo) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "🎉 Ignis 组件更新成功！适配 Obsidian: ${info.supportedObsidianVersion}",
                                    Toast.LENGTH_LONG
                                ).show()
                                updateIgnisStatus()
                                dialog.dismiss()
                            }
                        }

                        override fun onError(error: String) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "更新失败: $error", Toast.LENGTH_LONG).show()
                                dialogBinding.btnStartUpdate.isEnabled = true
                                dialogBinding.btnCancel.isEnabled = true
                                dialogBinding.spMirror.isEnabled = true
                            }
                        }
                    }
                )
            }
        }

        dialog.show()
    }

    private fun showDownloadGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("💡 提示：核心尚未下载")
            .setMessage("首次运行需要下载 Obsidian 官方核心包并自动解压部署，是否立即开始下载？")
            .setPositiveButton("立即下载") { _, _ ->
                showDownloadCoreDialog()
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun showDownloadCoreDialog() {
        val dialogBinding = DialogDownloadObsidianBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        // 填充版本选项（置顶 Ignis 适配推荐版本）
        val versions = downloader.getAvailableVersions()
        val versionAdapter = createObsidianSpinnerAdapter(versions)
        dialogBinding.spVersion.adapter = versionAdapter

        // 填充镜像加速选项
        val mirrors = ObsidianDownloader.MIRROR_PRESETS.map { it.first }
        val mirrorAdapter = createObsidianSpinnerAdapter(mirrors)
        dialogBinding.spMirror.adapter = mirrorAdapter

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnStartDownload.setOnClickListener {
            val selectedVer = versions[dialogBinding.spVersion.selectedItemPosition]
            val mirrorPrefix = ObsidianDownloader.MIRROR_PRESETS[dialogBinding.spMirror.selectedItemPosition].second

            dialogBinding.btnStartDownload.isEnabled = false
            dialogBinding.btnCancel.isEnabled = false
            dialogBinding.layoutProgress.visibility = View.VISIBLE
            dialogBinding.spVersion.isEnabled = false
            dialogBinding.spMirror.isEnabled = false

            lifecycleScope.launch {
                downloader.downloadAndInstall(
                    version = selectedVer,
                    mirrorPrefix = mirrorPrefix,
                    callback = object : ObsidianDownloader.DownloadCallback {
                        override fun onProgress(percentage: Float, downloadedBytes: Long, totalBytes: Long, speed: String) {
                            runOnUiThread {
                                dialogBinding.pbDownload.progress = percentage.toInt()
                                val mbDone = String.format("%.1f", downloadedBytes / 1024f / 1024f)
                                val mbTotal = if (totalBytes > 0) String.format("%.1f", totalBytes / 1024f / 1024f) else "?"
                                dialogBinding.tvProgressStatus.text = "下载中: $mbDone MB / $mbTotal MB @ $speed (${percentage.toInt()}%)"
                            }
                        }

                        override fun onExtractProgress(extracted: Int, total: Int, currentFile: String) {
                            runOnUiThread {
                                val pct = if (total > 0) (extracted * 100 / total) else 0
                                dialogBinding.pbDownload.progress = pct
                                dialogBinding.tvProgressStatus.text = "正在解压核心文件: $extracted / $total ($pct%)"
                            }
                        }

                        override fun onLog(message: String) {
                            runOnUiThread {
                                dialogBinding.tvLog.append("$message\n")
                            }
                        }

                        override fun onSuccess(version: String) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🎉 Obsidian 核心部署成功！", Toast.LENGTH_LONG).show()
                                updateCoreStatus()
                                dialog.dismiss()
                            }
                        }

                        override fun onError(error: String) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "错误: $error", Toast.LENGTH_LONG).show()
                                dialogBinding.btnStartDownload.isEnabled = true
                                dialogBinding.btnCancel.isEnabled = true
                                dialogBinding.spVersion.isEnabled = true
                                dialogBinding.spMirror.isEnabled = true
                            }
                        }
                    }
                )
            }
        }

        dialog.show()
    }

    private fun initVaultControls() {
        binding.btnManageVaults.setOnClickListener {
            showManageVaultsDialog()
        }
        binding.btnOpenLocalVault.setOnClickListener {
            showOpenLocalVaultDialog()
        }
        refreshVaultList()
    }

    private fun refreshVaultList() {
        val vaults = vaultManager.listVaults()
        val currentId = vaultManager.getDefaultVaultId()
        val currentVault = vaults.firstOrNull { it.id == currentId }

        if (currentVault != null && currentVault.isExternal) {
            binding.tvVaultInfo.text = "当前知识库: ${currentVault.name}\n(手机本地路径: ${currentVault.path})"
        } else {
            binding.tvVaultInfo.text = "当前默认知识库: $currentId"
        }

        val displayNames = vaults.map { if (it.isExternal) "📂 ${it.name} (本地)" else it.name }
        val adapter = createObsidianSpinnerAdapter(displayNames)
        binding.spVaults.adapter = adapter

        val currentIdx = vaults.indexOfFirst { it.id == currentId }
        if (currentIdx >= 0) {
            binding.spVaults.setSelection(currentIdx)
        }

        binding.spVaults.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in vaults.indices) {
                    val selected = vaults[position]
                    if (selected.id != vaultManager.getDefaultVaultId()) {
                        vaultManager.setCurrentVaultId(selected.id)
                        if (selected.isExternal) {
                            binding.tvVaultInfo.text = "当前知识库: ${selected.name}\n(手机本地路径: ${selected.path})"
                        } else {
                            binding.tvVaultInfo.text = "当前默认知识库: ${selected.name}"
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── 管理库弹窗 ────────────────────────────────────────────────────────────

    private fun showManageVaultsDialog() {
        val vaults = vaultManager.listVaults()
        if (vaults.isEmpty()) {
            Toast.makeText(this, "暂无知识库", Toast.LENGTH_SHORT).show()
            return
        }

        // 自定义 ListView，右侧显示删除图标
        val listView = android.widget.ListView(this)
        listView.divider = null
        listView.dividerHeight = 0

        val dialog = AlertDialog.Builder(this)
            .setTitle("🗂️ 管理知识库")
            .setView(listView)
            .setNegativeButton("关闭", null)
            .create()

        val adapter = object : ArrayAdapter<VaultManager.VaultItem>(
            this, R.layout.item_vault_manage, vaults
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_vault_manage, parent, false)
                val vault = vaults[position]

                val tvName = view.findViewById<android.widget.TextView>(R.id.tvVaultName)
                val btnDelete = view.findViewById<android.widget.ImageButton>(R.id.btnDeleteVault)

                val label = when {
                    vault.id == "DefaultVault" -> "🔒 ${vault.name}（不可删除）"
                    vault.isExternal           -> "📂 ${vault.name}（本地引用）"
                    else                       -> "📁 ${vault.name}"
                }
                tvName.text = label

                if (vault.id == "DefaultVault") {
                    btnDelete.visibility = android.view.View.INVISIBLE
                    btnDelete.setOnClickListener(null)
                } else {
                    btnDelete.visibility = android.view.View.VISIBLE
                    btnDelete.setOnClickListener {
                        dialog.dismiss()
                        showDeleteVaultConfirmDialog(vault)
                    }
                }

                return view
            }
        }

        listView.adapter = adapter
        dialog.show()
    }

    private fun showDeleteVaultConfirmDialog(vault: VaultManager.VaultItem) {
        val message = if (vault.isExternal) {
            "确认移除对知识库「${vault.name}」的引用？\n\n路径: ${vault.path}\n\n⚠️ 仅移除引用关联，手机上的原始文件不会被删除。"
        } else {
            "确认从列表中移除知识库「${vault.name}」？\n\n路径: ${vault.path}\n\n⚠️ 仅将其从列表中隐藏，实际文件不会被删除。"
        }
        AlertDialog.Builder(this)
            .setTitle("移除知识库")
            .setMessage(message)
            .setPositiveButton("确认移除") { _, _ ->
                val success = vaultManager.removeVaultReference(vault.id)
                if (success) {
                    refreshVaultList()
                    Toast.makeText(this, "已移除「${vault.name}」的引用", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun <T> createObsidianSpinnerAdapter(items: List<T>): ArrayAdapter<T> {
        return ArrayAdapter(this, R.layout.item_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
    }

    private fun showCreateVaultDialog() {
        val dialogBinding = DialogCreateVaultBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancelVault.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmVault.setOnClickListener {
            val name = dialogBinding.etVaultName.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "知识库名称不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val success = vaultManager.createVault(name)
            if (success) {
                vaultManager.setCurrentVaultId(name)
                refreshVaultList()
                Toast.makeText(this, "知识库 [$name] 创建成功！", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "创建失败，可能存在同名知识库或名称不合法", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showOpenLocalVaultDialog() {
        val dialogBinding = DialogOpenLocalVaultBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        // 1. 系统文件选择器
        dialogBinding.btnBrowseSystem.setOnClickListener {
            dialog.dismiss()
            openDocumentTreeLauncher.launch(null)
        }

        // 2. 常用预设快捷填入
        dialogBinding.btnPresetDocs.setOnClickListener {
            val docs = File(Environment.getExternalStorageDirectory(), "Documents")
            dialogBinding.etLocalPath.setText(docs.absolutePath)
        }
        dialogBinding.btnPresetDownload.setOnClickListener {
            val dl = File(Environment.getExternalStorageDirectory(), "Download")
            dialogBinding.etLocalPath.setText(dl.absolutePath)
        }
        dialogBinding.btnPresetRoot.setOnClickListener {
            dialogBinding.etLocalPath.setText(Environment.getExternalStorageDirectory().absolutePath)
        }

        dialogBinding.btnCancelLocalVault.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmLocalVault.setOnClickListener {
            val pathStr = dialogBinding.etLocalPath.text.toString().trim()
            val alias = dialogBinding.etLocalVaultName.text.toString().trim()

            if (pathStr.isBlank()) {
                Toast.makeText(this, "请输入或选择本地文件夹路径", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetDir = File(pathStr)
            if (!targetDir.exists()) {
                AlertDialog.Builder(this)
                    .setTitle("📁 文件夹不存在")
                    .setMessage("指定的本地路径不存在，是否立即创建该文件夹并加载？\n\n$pathStr")
                    .setPositiveButton("创建并加载") { _, _ ->
                        try {
                            targetDir.mkdirs()
                        } catch (_: Exception) {}
                        dialog.dismiss()
                        onLocalPathSelected(targetDir.absolutePath, alias)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            dialog.dismiss()
            onLocalPathSelected(targetDir.absolutePath, alias)
        }

        dialog.show()
    }

    private fun onLocalPathSelected(path: String, customAlias: String = "") {
        val dir = File(path)
        if (!dir.exists()) {
            try {
                dir.mkdirs()
            } catch (_: Exception) {}
        }

        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "指定的路径不是有效文件夹: $path", Toast.LENGTH_LONG).show()
            return
        }

        // 检查是否为外部共享存储目录（非本应用沙盒私有目录）
        val appSpecificDir = getExternalFilesDir(null)?.absolutePath ?: ""
        val internalDir = filesDir.absolutePath
        val isAppPrivate = (appSpecificDir.isNotEmpty() && dir.absolutePath.startsWith(appSpecificDir)) ||
                dir.absolutePath.startsWith(internalDir)

        if (!isAppPrivate && !isExternalStorageAccessGranted()) {
            showManageStoragePermissionDialog(dir, customAlias)
            return
        }

        val vaultName = if (customAlias.isNotBlank()) customAlias else dir.name.ifBlank { "LocalVault" }
        val success = vaultManager.addExternalVault(vaultName, dir)
        if (success) {
            vaultManager.setCurrentVaultId(vaultName)
            refreshVaultList()
            Toast.makeText(this, "🎉 成功加载本地知识库: [$vaultName]！", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "加载知识库失败，请检查文件夹读取权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManageStoragePermissionDialog(dir: File, customAlias: String) {
        pendingVaultLoad = Pair(dir.absolutePath, customAlias)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("🔒 需要【所有文件访问权限】")
                .setMessage("您选择的是手机外部共享目录：\n${dir.absolutePath}\n\n⚠️ 重要提示：\nAndroid 11+ 系统为了隐私安全保护，默认会向第三方应用隐藏外部共享目录中的 Markdown 等文档文件，导致网页只能看到文件夹而无法显示笔记！\n\n请在接下来的系统设置页面中允许本应用的【所有文件访问权限】，授予后方可正常列出并读写本地 Markdown 笔记。")
                .setPositiveButton("立即去授权") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("取消") { _, _ ->
                    pendingVaultLoad = null
                }
                .show()
        } else {
            requestLegacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}
