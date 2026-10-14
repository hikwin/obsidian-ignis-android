package com.ignis.obsidian.server

import android.content.Context
import android.util.Base64
import com.ignis.obsidian.util.MimeUtils
import com.ignis.obsidian.util.ObsidianDownloader
import com.ignis.obsidian.util.PathUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

class IgnisHttpServer(
    private val context: Context,
    port: Int = 8080,
    private var accessCode: String = ""
) : NanoHTTPD(port) {

    companion object {
        const val AUTH_COOKIE_NAME = "ignis_session_token"

        fun computeAuthToken(code: String): String {
            if (code.isBlank()) return ""
            val input = "IGNIS_SECURITY_V1:$code"
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val vaultManager = VaultManager(context)
    private val downloader = ObsidianDownloader(context)
    private val gson = Gson()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val settingsPrefs = context.getSharedPreferences("ignis_settings", Context.MODE_PRIVATE)

    init {
        if (accessCode.isBlank()) {
            accessCode = settingsPrefs.getString("server_access_code", "") ?: ""
        }
    }

    fun setAccessCode(code: String) {
        this.accessCode = code
    }

    fun isAuthEnabled(): Boolean = accessCode.isNotBlank()

    fun isAuthorized(session: IHTTPSession): Boolean {
        if (!isAuthEnabled()) return true
        val expectedToken = computeAuthToken(accessCode)

        // 1. 检查 Cookie 头: ignis_session_token=<token>
        val cookieHeader = session.headers["cookie"]
        if (!cookieHeader.isNullOrBlank()) {
            val match = Regex("""(?:^|;\s*)ignis_session_token=([a-zA-Z0-9_\-]+)""").find(cookieHeader)
            if (match != null && match.groupValues[1] == expectedToken) {
                return true
            }
        }

        // 2. 检查 NanoHTTPD Cookie 处理器
        try {
            val cookieVal = session.cookies.read(AUTH_COOKIE_NAME)
            if (cookieVal == expectedToken) {
                return true
            }
        } catch (_: Exception) {}

        // 3. 检查 Authorization: Bearer <token>
        val authHeader = session.headers["authorization"]
        if (authHeader?.startsWith("Bearer ", ignoreCase = true) == true) {
            val token = authHeader.substring(7).trim()
            if (token == expectedToken) {
                return true
            }
        }

        // 4. 检查 URL 携带参数 ?token=... 或 ?access_code=...
        val paramToken = session.parameters["token"]?.firstOrNull()
        if (paramToken == expectedToken) {
            return true
        }
        val paramCode = session.parameters["access_code"]?.firstOrNull()
        if (!paramCode.isNullOrBlank() && paramCode == accessCode) {
            return true
        }

        return false
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        try {
            // 0. 认证验证与登出公共接口
            if (uri == "/api/auth/verify" || uri == "/api/auth/login") {
                return handleAuthVerify(session)
            }
            if (uri == "/api/auth/logout") {
                return handleAuthLogout()
            }

            // 静态资源白名单放行（保证登录页面的图标与样式正常分发）
            if (uri.startsWith("/assets/") || uri == "/favicon.png" || uri == "/favicon.ico") {
                val staticRes = handleStaticAssets(uri)
                if (staticRes.status != Response.Status.NOT_FOUND) {
                    return staticRes
                }
            }

            // 1. 访问码安全验证拦截
            if (isAuthEnabled() && !isAuthorized(session)) {
                // 如果是页面入口，分发访问码验证页面
                if (uri == "/" || uri == "/index.html" || uri == "/login") {
                    return renderLoginPage()
                }

                // 如果是 API 请求，返回 401 提示需验证
                if (uri.startsWith("/api/")) {
                    val errorJson = gson.toJson(mapOf(
                        "success" to false,
                        "error" to "Unauthorized: Access code required",
                        "authRequired" to true
                    ))
                    val res = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json; charset=utf-8", errorJson)
                    res.addHeader("Cache-Control", "no-cache")
                    return res
                }

                // 其他媒体附件请求返回 401
                val res = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8", "Unauthorized: Access code required")
                res.addHeader("Cache-Control", "no-cache")
                return res
            }

            // 已通过验证后，若直接访问 /login 则重定向至主页
            if (uri == "/login") {
                val res = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                res.addHeader("Location", "/")
                return res
            }

            // 2. Vault 媒体与静态附件文件 /vault-files/*
            if (uri.startsWith("/vault-files/")) {
                return handleVaultFiles(uri)
            }

            // 3. API 路由 /api/*
            if (uri.startsWith("/api/")) {
                return handleApi(session)
            }

            // 4. 主界面入口 / 或 /index.html
            if (uri == "/" || uri == "/index.html") {
                val response = handleIndexHtml()
                // 若通过 URL 参数带 access_code 访问成功，响应头下发 Cookie 保持会话
                if (isAuthEnabled()) {
                    val expectedToken = computeAuthToken(accessCode)
                    response.addHeader("Set-Cookie", "$AUTH_COOKIE_NAME=$expectedToken; Path=/; Max-Age=2592000; SameSite=Lax")
                }
                return response
            }

            // 5. 静态资源分发（覆盖 /assets/*, /lib/*, /i18n/*, /app.css, /worker.js, /favicon.png 等所有前端静态资源）
            val staticRes = handleStaticAssets(uri)
            if (staticRes.status != Response.Status.NOT_FOUND) {
                return staticRes
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: $uri")
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to (e.message ?: "Internal Server Error")))
            )
        }
    }

    /**
     * 处理访问码校验请求
     */
    private fun handleAuthVerify(session: IHTTPSession): Response {
        if (!isAuthEnabled()) {
            val data = mapOf(
                "success" to true,
                "message" to "免密访问已开启",
                "redirect" to "/"
            )
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(data))
        }

        val bodyJson = parseRequestBody(session)
        val inputCode = bodyJson.get("code")?.asString
            ?: bodyJson.get("accessCode")?.asString
            ?: bodyJson.get("password")?.asString
            ?: session.parameters["code"]?.firstOrNull()
            ?: session.parameters["accessCode"]?.firstOrNull()
            ?: session.parameters["password"]?.firstOrNull()
            ?: ""

        if (inputCode == accessCode) {
            val token = computeAuthToken(accessCode)
            val data = mapOf(
                "success" to true,
                "token" to token,
                "message" to "验证通过",
                "redirect" to "/"
            )
            val res = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(data))
            res.addHeader("Set-Cookie", "$AUTH_COOKIE_NAME=$token; Path=/; Max-Age=2592000; SameSite=Lax")
            res.addHeader("Cache-Control", "no-cache")
            return res
        } else {
            val data = mapOf(
                "success" to false,
                "message" to "访问码错误，请重新输入"
            )
            val res = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json; charset=utf-8", gson.toJson(data))
            res.addHeader("Cache-Control", "no-cache")
            return res
        }
    }

    /**
     * 退出登录清理 Cookie
     */
    private fun handleAuthLogout(): Response {
        val data = mapOf("success" to true, "message" to "已退出登录")
        val res = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(data))
        res.addHeader("Set-Cookie", "$AUTH_COOKIE_NAME=; Path=/; Max-Age=0; SameSite=Lax")
        res.addHeader("Cache-Control", "no-cache")
        return res
    }

    /**
     * 动态生成并渲染优雅的安全访问码验证页
     */
    private fun renderLoginPage(): Response {
        val loginHtml = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover"/>
              <title>安全访问验证 - OBServer</title>
              <link rel="icon" type="image/png" href="/assets/favicon.png"/>
              <style>
                :root {
                  --primary: #7c3aed;
                  --primary-hover: #6d28d9;
                  --accent: #a855f7;
                  --bg: #0f0e17;
                  --card-bg: rgba(24, 22, 38, 0.84);
                  --border: rgba(139, 92, 246, 0.22);
                  --text: #f4f4f5;
                  --text-sub: #a1a1aa;
                  --input-bg: rgba(15, 14, 23, 0.7);
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  min-height: 100vh;
                  background: var(--bg) radial-gradient(circle at 50% 25%, rgba(124, 58, 237, 0.2) 0%, rgba(15, 14, 23, 0.98) 75%);
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                  color: var(--text);
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  padding: 20px;
                  overflow-x: hidden;
                }
                .card {
                  width: 100%;
                  max-width: 400px;
                  background: var(--card-bg);
                  backdrop-filter: blur(28px);
                  -webkit-backdrop-filter: blur(28px);
                  border: 1px solid var(--border);
                  border-radius: 24px;
                  padding: 40px 32px;
                  box-shadow: 0 25px 60px -15px rgba(0, 0, 0, 0.65), 0 0 40px -10px rgba(124, 58, 237, 0.3);
                  text-align: center;
                  position: relative;
                  overflow: hidden;
                  animation: card-appear 0.4s cubic-bezier(0.16, 1, 0.3, 1);
                }
                .card::before {
                  content: "";
                  position: absolute;
                  top: 0; left: 0; right: 0; height: 3px;
                  background: linear-gradient(90deg, #7c3aed, #ec4899, #6366f1);
                }
                @keyframes card-appear {
                  from { opacity: 0; transform: translateY(16px) scale(0.98); }
                  to { opacity: 1; transform: translateY(0) scale(1); }
                }
                .logo-wrap {
                  position: relative;
                  display: inline-block;
                  margin-bottom: 20px;
                }
                .logo {
                  width: 76px;
                  height: 76px;
                  border-radius: 20px;
                  box-shadow: 0 10px 25px rgba(124, 58, 237, 0.4);
                  animation: logo-glow 3.5s ease-in-out infinite;
                }
                @keyframes logo-glow {
                  0%, 100% { transform: scale(1); box-shadow: 0 10px 25px rgba(124, 58, 237, 0.4); }
                  50% { transform: scale(1.04); box-shadow: 0 14px 35px rgba(168, 85, 247, 0.6); }
                }
                .badge {
                  display: inline-flex;
                  align-items: center;
                  gap: 6px;
                  padding: 4px 14px;
                  background: rgba(124, 58, 237, 0.16);
                  border: 1px solid rgba(139, 92, 246, 0.35);
                  border-radius: 20px;
                  color: #c4b5fd;
                  font-size: 12px;
                  font-weight: 600;
                  margin-bottom: 14px;
                  letter-spacing: 0.02em;
                }
                .title {
                  font-size: 22px;
                  font-weight: 700;
                  color: #ffffff;
                  letter-spacing: -0.01em;
                  margin-bottom: 6px;
                }
                .subtitle {
                  font-size: 13px;
                  color: var(--text-sub);
                  line-height: 1.5;
                  margin-bottom: 28px;
                }
                .form-group {
                  position: relative;
                  margin-bottom: 18px;
                  text-align: left;
                }
                .input-box {
                  position: relative;
                  display: flex;
                  align-items: center;
                }
                .input-box input {
                  width: 100%;
                  padding: 13px 46px 13px 16px;
                  background: var(--input-bg);
                  border: 1px solid rgba(255, 255, 255, 0.12);
                  border-radius: 12px;
                  color: #ffffff;
                  font-size: 15px;
                  outline: none;
                  transition: all 0.2s ease;
                }
                .input-box input:focus {
                  border-color: #8b5cf6;
                  background: rgba(15, 14, 23, 0.95);
                  box-shadow: 0 0 0 3px rgba(139, 92, 246, 0.25);
                }
                .toggle-eye {
                  position: absolute;
                  right: 12px;
                  background: none;
                  border: none;
                  color: #71717a;
                  cursor: pointer;
                  padding: 6px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  transition: color 0.2s;
                }
                .toggle-eye:hover { color: #c4b5fd; }
                .btn-submit {
                  width: 100%;
                  padding: 14px 20px;
                  background: linear-gradient(135deg, #7c3aed 0%, #6366f1 100%);
                  border: none;
                  border-radius: 12px;
                  color: #ffffff;
                  font-size: 15px;
                  font-weight: 600;
                  cursor: pointer;
                  transition: all 0.25s ease;
                  box-shadow: 0 8px 25px -4px rgba(124, 58, 237, 0.5);
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  gap: 8px;
                }
                .btn-submit:hover {
                  transform: translateY(-1px);
                  box-shadow: 0 10px 30px -4px rgba(124, 58, 237, 0.7);
                }
                .btn-submit:active {
                  transform: translateY(0);
                }
                .btn-submit:disabled {
                  opacity: 0.6;
                  cursor: not-allowed;
                  transform: none;
                }
                .alert {
                  display: none;
                  padding: 11px 14px;
                  border-radius: 10px;
                  font-size: 13px;
                  margin-bottom: 20px;
                  text-align: left;
                  background: rgba(239, 68, 68, 0.15);
                  border: 1px solid rgba(239, 68, 68, 0.35);
                  color: #fca5a5;
                }
                .shake {
                  animation: shake 0.4s ease-in-out;
                }
                @keyframes shake {
                  0%, 100% { transform: translateX(0); }
                  20%, 60% { transform: translateX(-8px); }
                  40%, 80% { transform: translateX(8px); }
                }
                .spinner {
                  display: none;
                  width: 16px;
                  height: 16px;
                  border: 2px solid rgba(255, 255, 255, 0.3);
                  border-top-color: #ffffff;
                  border-radius: 50%;
                  animation: spin 0.8s linear infinite;
                }
                @keyframes spin {
                  to { transform: rotate(360deg); }
                }
                .footer {
                  margin-top: 24px;
                  font-size: 12px;
                  color: #52525b;
                }
              </style>
            </head>
            <body>
              <div class="card" id="authCard">
                <div class="logo-wrap">
                  <img src="/assets/favicon.png" alt="Logo" class="logo" onerror="this.style.display='none'"/>
                </div>
                <div>
                  <span class="badge">🔒 安全访问保护</span>
                </div>
                <h1 class="title">OBServer</h1>
                <p class="subtitle">该本地 Web 实例已启用安全保护<br>请输入访问码以解锁知识库</p>

                <div id="alertMsg" class="alert"></div>

                <form id="authForm" autocomplete="off">
                  <div class="form-group">
                    <div class="input-box">
                      <input type="password" id="inputCode" name="code" placeholder="输入访问码..." autofocus required />
                      <button type="button" class="toggle-eye" id="btnToggleEye" title="显示/隐藏访问码">
                        <svg id="eyeIcon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                          <circle cx="12" cy="12" r="3"></circle>
                        </svg>
                      </button>
                    </div>
                  </div>
                  <button type="submit" class="btn-submit" id="btnSubmit">
                    <span class="spinner" id="btnSpinner"></span>
                    <span id="btnText">进入知识库</span>
                  </button>
                </form>

                <div class="footer">OBServer Android &bull; 本地安全服务</div>
              </div>

              <script>
                const form = document.getElementById('authForm');
                const input = document.getElementById('inputCode');
                const alertEl = document.getElementById('alertMsg');
                const card = document.getElementById('authCard');
                const btnSubmit = document.getElementById('btnSubmit');
                const btnSpinner = document.getElementById('btnSpinner');
                const btnText = document.getElementById('btnText');
                const btnToggleEye = document.getElementById('btnToggleEye');

                btnToggleEye.addEventListener('click', () => {
                  const isPassword = input.type === 'password';
                  input.type = isPassword ? 'text' : 'password';
                  btnToggleEye.style.color = isPassword ? '#c4b5fd' : '#71717a';
                });

                form.addEventListener('submit', async (e) => {
                  e.preventDefault();
                  const code = input.value.trim();
                  if (!code) return;

                  btnSubmit.disabled = true;
                  btnSpinner.style.display = 'inline-block';
                  btnText.textContent = '正在验证...';
                  alertEl.style.display = 'none';
                  card.classList.remove('shake');

                  try {
                    const res = await fetch('/api/auth/verify', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json; charset=utf-8' },
                      body: JSON.stringify({ code: code })
                    });
                    const data = await res.json();
                    if (data.success) {
                      btnText.textContent = '验证成功，正在进入...';
                      btnSubmit.style.background = 'linear-gradient(135deg, #10b981 0%, #059669 100%)';
                      setTimeout(() => {
                        window.location.reload();
                      }, 250);
                    } else {
                      showError(data.message || '访问码错误，请重新输入');
                    }
                  } catch (err) {
                    showError('网络连接异常，请重试');
                  } finally {
                    if (!btnSubmit.disabled) return;
                    setTimeout(() => {
                      if (btnText.textContent !== '验证成功，正在进入...') {
                        btnSubmit.disabled = false;
                        btnSpinner.style.display = 'none';
                        btnText.textContent = '进入知识库';
                      }
                    }, 400);
                  }
                });

                function showError(msg) {
                  alertEl.textContent = msg;
                  alertEl.style.display = 'block';
                  btnSubmit.disabled = false;
                  btnSpinner.style.display = 'none';
                  btnText.textContent = '进入知识库';
                  card.classList.remove('shake');
                  void card.offsetWidth;
                  card.classList.add('shake');
                  input.focus();
                  input.select();
                }
              </script>
            </body>
            </html>
        """.trimIndent()

        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", loginHtml)
        res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return res
    }

    /**
     * 提取或生成 Obsidian 启动合规握手 Token (__obsidianTerms)，防止 Obsidian 1.14+ 启动退出白屏
     */
    private fun getObsidianTerms(): String {
        try {
            val obsidianDir = downloader.getObsidianDir()
            val mainJs = File(obsidianDir, "main.js")
            if (mainJs.exists()) {
                val text = mainJs.readText(Charsets.UTF_8)
                val match = Regex("""l\.ipcMain\.on\("terms",\w+=>\{\w+\.returnValue=(\w+)\}\)""").find(text)
                if (match != null) {
                    val varName = match.groupValues[1]
                    val defMatch = Regex("""(?<=^|[;\n,\(])$varName="([^"]+)"""").find(text)
                        ?: Regex("""$varName="([^"]+)"""").find(text)
                    if (defMatch != null) {
                        return defMatch.groupValues[1]
                    }
                }
            }
            val appJs = File(obsidianDir, "app.js")
            if (appJs.exists()) {
                val text = appJs.readText(Charsets.UTF_8)
                val match = Regex(""""([^"]+)"!==\w+\.ipcRenderer\.sendSync\("terms"\)""").find(text)
                    ?: Regex("""\w+\.ipcRenderer\.sendSync\("terms"\)!== "([^"]+)"""").find(text)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "I understand and agree that I am not allowed to distribute the Obsidian application, in any form, without explicit approval from the Obsidian team. I also understand that Obsidian is a registered trademark, and I cannot use it without explicit permission granted by the Obsidian team."
    }

    /**
     * 主界面 HTML 动态生成与渲染
     */
    private fun handleIndexHtml(): Response {
        val templateHtml = try {
            context.assets.open("web/server/index.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<!DOCTYPE html><html><body><h1>OBServer</h1><p>Template not found</p></body></html>"
        }

        val obsidianDir = downloader.getObsidianDir()
        val scripts = mutableListOf<String>()
        val indexHtmlFile = File(obsidianDir, "index.html")

        if (indexHtmlFile.exists()) {
            try {
                val content = indexHtmlFile.readText(Charsets.UTF_8)
                val regex = Regex("""<script[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
                val matches = regex.findAll(content)
                for (m in matches) {
                    val src = m.groupValues[1].trim().trimStart('/')
                    scripts.add("/assets/obsidian/$src")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 如果 index.html 解析不到脚本，使用标准完整的 14 个核心依赖脚本按序回退加载
        if (scripts.isEmpty()) {
            val defaultScripts = listOf(
                "lib/codemirror/codemirror.js",
                "lib/codemirror/overlay.js",
                "lib/codemirror/markdown.js",
                "lib/codemirror/cm-addons.js",
                "lib/codemirror/vim.js",
                "lib/codemirror/meta.min.js",
                "lib/moment.min.js",
                "lib/pixi.min.js",
                "lib/i18next.min.js",
                "lib/scrypt.js",
                "lib/turndown.js",
                "enhance.js",
                "i18n.js",
                "app.js"
            )
            for (s in defaultScripts) {
                if (File(obsidianDir, s).exists()) {
                    scripts.add("/assets/obsidian/$s")
                }
            }
            if (scripts.isEmpty() && File(obsidianDir, "app.js").exists()) {
                scripts.add("/assets/obsidian/app.js")
            }
        }

        val ignisMgr = com.ignis.obsidian.util.IgnisComponentManager(context)
        val ignisVer = ignisMgr.getInstalledIgnisVersion().replace(" ", "_")
        val ver = "${downloader.getInstalledVersion()}_$ignisVer"
        val termsStr = getObsidianTerms()

        var html = templateHtml
        html = html.replace("__OBSIDIAN_TERMS__", gson.toJson(termsStr))
        html = html.replace("__IGNIS_UI_SRC__", "/assets/ui/ignis-ui.js?v=$ver")
        html = html.replace("__SHIM_LOADER_SRC__", "/assets/shim/shim-loader.js?v=$ver")
        html = html.replace("__APP_CSS_SRC__", "/assets/obsidian/app.css?v=$ver")
        html = html.replace("assets/overrides.css", "/assets/server/overrides.css?v=$ver")
        html = html.replace("favicon.png", "/assets/favicon.png?v=$ver")
        html = html.replace("__OBSIDIAN_SCRIPTS__", gson.toJson(scripts))

        val currentVaultId = vaultManager.getDefaultVaultId()
        val baseScript = """
            <script>
            window.__IGNIS_APP_BASE__ = "";
            window.__IGNIS_URL_MODE__ = "restful";
            window.__IGNIS_SERVER_VAULT__ = ${gson.toJson(currentVaultId)};
            window.__obsidianTerms = ${gson.toJson(termsStr)};
            (function() {
              var base = "";
              var origin = window.location.origin;

              function rewriteUrl(url) {
                if (typeof url !== 'string') return url;
                var cleanUrl = url;
                if (cleanUrl.startsWith(origin)) {
                  cleanUrl = cleanUrl.substring(origin.length);
                }
                if (cleanUrl === '/worker.js' || cleanUrl === 'worker.js') {
                  return '/assets/obsidian/worker.js';
                }
                return url;
              }

              function patchHeaders(headers) {
                if (!headers) return;
                if (window.Headers && headers instanceof Headers) {
                  var ct = headers.get('content-type');
                  if (ct && ct.toLowerCase().indexOf('application/json') !== -1 && ct.toLowerCase().indexOf('charset') === -1) {
                    headers.set('content-type', ct + '; charset=utf-8');
                  }
                } else if (typeof headers === 'object') {
                  for (var k in headers) {
                    if (k.toLowerCase() === 'content-type') {
                      var v = headers[k];
                      if (v && v.toLowerCase().indexOf('application/json') !== -1 && v.toLowerCase().indexOf('charset') === -1) {
                        headers[k] = v + '; charset=utf-8';
                      }
                    }
                  }
                }
              }

              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function(input, init) {
                  if (typeof input === 'string') {
                    input = rewriteUrl(input);
                  } else if (input && input.url) {
                    input = new Request(rewriteUrl(input.url), input);
                  }
                  if (init && init.headers) {
                    patchHeaders(init.headers);
                  }
                  return origFetch.call(this, input, init);
                };
              }

              var origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url, async, user, pass) {
                if (typeof url === 'string') {
                  url = rewriteUrl(url);
                }
                return origOpen.call(this, method, url, async, user, pass);
              };

              var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
              if (origSetHeader) {
                XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                  if (header && header.toLowerCase() === 'content-type') {
                    if (value && value.toLowerCase().indexOf('application/json') !== -1 && value.toLowerCase().indexOf('charset') === -1) {
                      value = value + '; charset=utf-8';
                    }
                  }
                  return origSetHeader.call(this, header, value);
                };
              }

              var OrigWorker = window.Worker;
              if (OrigWorker) {
                window.Worker = function(scriptURL, options) {
                  if (typeof scriptURL === 'string') {
                    var cleanUrl = scriptURL;
                    if (cleanUrl.startsWith(origin)) {
                      cleanUrl = cleanUrl.substring(origin.length);
                    }
                    if (cleanUrl === '/worker.js' || cleanUrl === 'worker.js') {
                      scriptURL = '/assets/obsidian/worker.js';
                    }
                  }
                  return new OrigWorker(scriptURL, options);
                };
                window.Worker.prototype = OrigWorker.prototype;
              }
            })();
            </script>
        """.trimIndent()

        html = html.replace("<head>", "<head>\n$baseScript")

        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        res.addHeader("Cache-Control", "no-cache")
        return res
    }

    /**
     * 静态资源分发（覆盖 Obsidian 核心文件与内置 Ignis 静态资源）
     */
    private fun handleStaticAssets(uri: String): Response {
        val cleanUri = uri.substringBefore('?').trimStart('/')

        val relUnderObsidian = if (cleanUri.startsWith("assets/obsidian/")) {
            cleanUri.removePrefix("assets/obsidian/")
        } else if (cleanUri.startsWith("assets/")) {
            cleanUri.removePrefix("assets/")
        } else {
            cleanUri
        }

        // 1. 优先从下载解压的 Obsidian 核心目录查找
        val obsidianDir = downloader.getObsidianDir()
        val candidatesInObsidian = listOf(
            File(obsidianDir, relUnderObsidian),
            File(obsidianDir, cleanUri)
        )
        for (targetFile in candidatesInObsidian) {
            if (targetFile.exists() && targetFile.isFile) {
                val mime = MimeUtils.getMimeType(targetFile.name)
                val fis = FileInputStream(targetFile)
                val res = newFixedLengthResponse(Response.Status.OK, mime, fis, targetFile.length())
                res.addHeader("Cache-Control", "public, max-age=3600")
                return res
            }
        }

        // 2. 检查动态更新的 Ignis Web 组件 (例如 shim/shim-loader.js, ui/ignis-ui.js)
        val ignisWebDir = File(context.filesDir, "ignis_web")
        if (ignisWebDir.exists()) {
            val relIgnis = if (cleanUri.startsWith("assets/")) cleanUri.removePrefix("assets/") else cleanUri
            val ignisCandidates = listOf(
                File(ignisWebDir, relIgnis),
                File(ignisWebDir, cleanUri)
            )
            for (targetFile in ignisCandidates) {
                if (targetFile.exists() && targetFile.isFile && targetFile.length() > 0) {
                    val mime = MimeUtils.getMimeType(targetFile.name)
                    val fis = FileInputStream(targetFile)
                    val res = newFixedLengthResponse(Response.Status.OK, mime, fis, targetFile.length())
                    res.addHeader("Cache-Control", "no-cache")
                    return res
                }
            }
        }

        // 3. 从内置 assets/web 目录查找
        val assetPaths = mutableListOf<String>()
        if (cleanUri == "favicon.ico" || cleanUri == "favicon.png" || cleanUri == "assets/favicon.png") {
            assetPaths.add("web/favicon.png")
        } else {
            if (cleanUri.startsWith("assets/")) {
                val sub = cleanUri.removePrefix("assets/")
                assetPaths.add("web/$sub")
                assetPaths.add("web/server/$sub")
            } else {
                assetPaths.add("web/$cleanUri")
                assetPaths.add("web/server/$cleanUri")
            }
        }

        for (path in assetPaths) {
            try {
                val inputStream = context.assets.open(path)
                val mime = MimeUtils.getMimeType(path)
                val length = try { inputStream.available().toLong() } catch (_: Exception) { -1L }
                val res = if (length >= 0) {
                    newFixedLengthResponse(Response.Status.OK, mime, inputStream, length)
                } else {
                    newChunkedResponse(Response.Status.OK, mime, inputStream)
                }
                res.addHeader("Cache-Control", "public, max-age=3600")
                return res
            } catch (_: Exception) {}
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $uri")
    }

    /**
     * 读取 Vault 内的静态媒体文件
     */
    private fun handleVaultFiles(uri: String): Response {
        val sub = uri.removePrefix("/vault-files/").trimStart('/')
        val parts = sub.split("/").toMutableList()
        if (parts.isEmpty() || parts[0].isEmpty()) {
            return jsonError("Missing vault ID", Response.Status.BAD_REQUEST)
        }
        val vaultId = URLDecoder.decode(parts.removeAt(0), "UTF-8")
        val vaultPath = vaultManager.getVaultPath(vaultId)
            ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)

        val relPath = parts.joinToString("/")
        val target = PathUtils.resolveVaultPath(vaultPath, relPath)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (!target.exists() || target.isDirectory) {
            return jsonError("File not found", Response.Status.NOT_FOUND)
        }

        val mime = MimeUtils.getMimeType(target.name)
        val fis = FileInputStream(target)
        return newFixedLengthResponse(Response.Status.OK, mime, fis, target.length())
    }

    /**
     * 处理 REST API 请求
     */
    private fun handleApi(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        val queryParams = session.parameters.mapValues { it.value.firstOrNull() ?: "" }
        val bodyJson = parseRequestBody(session)

        return when (uri) {
            "/api/bootstrap" -> handleBootstrap(queryParams)
            "/api/fs/stat" -> handleFsStat(queryParams)
            "/api/fs/readdir" -> handleFsReaddir(queryParams)
            "/api/fs/readFile" -> handleFsReadFile(queryParams)
            "/api/fs/writeFile" -> handleFsWriteFile(bodyJson)
            "/api/fs/appendFile" -> handleFsAppendFile(bodyJson)
            "/api/fs/mkdir" -> handleFsMkdir(bodyJson)
            "/api/fs/rename" -> handleFsRename(bodyJson)
            "/api/fs/copyFile" -> handleFsCopyFile(bodyJson)
            "/api/fs/unlink" -> handleFsUnlink(queryParams, bodyJson)
            "/api/fs/rmdir" -> handleFsRmdir(queryParams, bodyJson)
            "/api/fs/rm" -> handleFsRm(queryParams, bodyJson)
            "/api/fs/access" -> handleFsAccess(queryParams)
            "/api/fs/utimes" -> handleFsUtimes(bodyJson)
            "/api/fs/batch-read" -> handleFsBatchRead(bodyJson)
            "/api/fs/tree" -> handleFsTree(queryParams)
            "/api/fs/download" -> handleFsDownload(queryParams)
            "/api/fs/download-zip" -> handleFsDownloadZip(queryParams)

            "/api/vault/list" -> handleVaultList()
            "/api/vault/info" -> handleVaultInfo(queryParams)
            "/api/vault/create" -> handleVaultCreate(bodyJson)
            "/api/vault/rename" -> handleVaultRename(bodyJson)
            "/api/vault/remove" -> handleVaultRemove(queryParams)

            "/api/proxy" -> handleProxy(bodyJson)
            "/api/version" -> handleVersion()
            "/api/settings" -> handleSettings(method, bodyJson)
            "/api/plugins" -> jsonResponse(emptyList<Any>())

            else -> jsonError("API endpoint not found: $uri", Response.Status.NOT_FOUND)
        }
    }

    private fun parseRequestBody(session: IHTTPSession): JsonObject {
        val files = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            try {
                // 解决中文乱码核心根因：
                // NanoHTTPD 默认在缺少 charset 时将 ContentType.getEncoding() 硬编码回退为 "US-ASCII"，
                // 导致 POST/PUT 解析请求体时将所有 UTF-8 中文字节（> 0x7F）强转并替换为 '?'（0x3F 乱码）。
                // 在 session.parseBody 之前，强制将 content-type 请求头规范化补充为 charset=UTF-8。
                val headers = session.headers as? MutableMap<String, String>
                val ct = headers?.get("content-type")
                if (ct == null) {
                    headers?.put("content-type", "application/json; charset=UTF-8")
                } else if (!ct.contains("charset=", ignoreCase = true)) {
                    headers["content-type"] = "$ct; charset=UTF-8"
                }
                session.parseBody(files)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val postData = files["postData"] ?: files["content"]?.let { tmpPath ->
            try {
                File(tmpPath).readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        return if (!postData.isNullOrBlank()) {
            try {
                JsonParser.parseString(postData).asJsonObject
            } catch (_: Exception) {
                JsonObject()
            }
        } else {
            JsonObject()
        }
    }

    private fun getVaultRoot(params: Map<String, String>, body: JsonObject? = null): File? {
        val vaultId = params["vault"]
            ?: body?.get("vault")?.asString
            ?: vaultManager.getDefaultVaultId()
        return vaultManager.getVaultPath(vaultId)
    }

    private fun handleBootstrap(params: Map<String, String>): Response {
        val vaultId = params["vault"] ?: vaultManager.getDefaultVaultId()
        val vaultPath = vaultManager.getVaultPath(vaultId)
            ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)

        val tree = walkTree(vaultPath)
        val vaultList = vaultManager.listVaults().map {
            mapOf("id" to it.id, "name" to it.name, "path" to it.path)
        }

        val data = mapOf(
            "vault" to mapOf(
                "id" to vaultId,
                "name" to vaultId,
                "path" to vaultPath.absolutePath,
                "platform" to "linux",
                "version" to downloader.getInstalledVersion()
            ),
            "vaultList" to vaultList,
            "tree" to tree,
            "plugins" to emptyList<Any>(),
            "virtualPlugins" to emptyList<Any>(),
            "settings" to mapOf(
                "contentCacheBytes" to 104857600,
                "inputCacheBytes" to 10485760,
                "inputCacheTtlMs" to 30000,
                "directFetchHosts" to emptyList<String>()
            )
        )
        return jsonResponse(data)
    }

    private fun handleFsStat(params: Map<String, String>): Response {
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = params["path"] ?: return jsonError("Missing path parameter", Response.Status.BAD_REQUEST)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Path traversal rejected", Response.Status.FORBIDDEN)
        val target = PathUtils.resolveWithMdFallback(resolved)

        if (!target.exists()) {
            return jsonError("ENOENT: no such file or directory", Response.Status.NOT_FOUND, "ENOENT")
        }

        val data = mapOf(
            "type" to if (target.isDirectory) "directory" else "file",
            "size" to target.length(),
            "mtime" to target.lastModified(),
            "ctime" to target.lastModified()
        )
        return jsonResponse(data)
    }

    private fun handleFsReaddir(params: Map<String, String>): Response {
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = params["path"] ?: ""
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Path traversal rejected", Response.Status.FORBIDDEN)

        if (!resolved.exists()) {
            return jsonError("ENOENT: no such file or directory", Response.Status.NOT_FOUND, "ENOENT")
        }
        if (!resolved.isDirectory) {
            return jsonError("ENOTDIR: not a directory", Response.Status.BAD_REQUEST, "ENOTDIR")
        }

        val entries = mutableListOf<Map<String, String>>()
        val files = resolved.listFiles() ?: emptyArray()
        for (f in files) {
            entries.add(mapOf(
                "name" to f.name,
                "type" to if (f.isDirectory) "directory" else "file"
            ))
        }
        return jsonResponse(entries)
    }

    private fun handleFsReadFile(params: Map<String, String>): Response {
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = params["path"] ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Path traversal rejected", Response.Status.FORBIDDEN)
        val target = PathUtils.resolveWithMdFallback(resolved)

        if (!target.exists()) {
            return jsonError("ENOENT: no such file or directory", Response.Status.NOT_FOUND, "ENOENT")
        }
        if (target.isDirectory) {
            return jsonError("EISDIR: illegal operation on a directory", Response.Status.BAD_REQUEST, "EISDIR")
        }

        val encoding = params["encoding"]
        val bytes = target.readBytes()
        if (encoding == "base64") {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return jsonResponse(mapOf("content" to b64))
        } else if (encoding == "utf8" || encoding == "utf-8") {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/plain; charset=utf-8",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } else {
            val mime = MimeUtils.getMimeType(target.name)
            return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    private fun handleFsWriteFile(body: JsonObject): Response {
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Path traversal rejected", Response.Status.FORBIDDEN)

        val parent = resolved.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val contentStr = body.get("content")?.asString ?: ""
        val isBase64 = body.get("base64")?.asBoolean == true

        if (isBase64) {
            val data = Base64.decode(contentStr, Base64.DEFAULT)
            resolved.writeBytes(data)
        } else {
            resolved.writeText(contentStr, Charsets.UTF_8)
        }

        return jsonResponse(mapOf(
            "ok" to true,
            "mtime" to resolved.lastModified(),
            "size" to resolved.length()
        ))
    }

    private fun handleFsAppendFile(body: JsonObject): Response {
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Path traversal rejected", Response.Status.FORBIDDEN)

        val parent = resolved.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val content = body.get("content")?.asString ?: ""
        FileOutputStream(resolved, true).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsMkdir(body: JsonObject): Response {
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Path traversal rejected", Response.Status.FORBIDDEN)

        if (!resolved.exists()) {
            resolved.mkdirs()
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsRename(body: JsonObject): Response {
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val oldPath = body.get("oldPath")?.asString ?: return jsonError("Missing oldPath", Response.Status.BAD_REQUEST)
        val newPath = body.get("newPath")?.asString ?: return jsonError("Missing newPath", Response.Status.BAD_REQUEST)

        val src = PathUtils.resolveVaultPath(vaultRoot, oldPath)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)
        val dst = PathUtils.resolveVaultPath(vaultRoot, newPath)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (!src.exists()) {
            return jsonError("ENOENT: no such file or directory", Response.Status.NOT_FOUND, "ENOENT")
        }

        dst.parentFile?.mkdirs()
        val ok = src.renameTo(dst)
        return if (ok) jsonResponse(mapOf("ok" to true)) else jsonError("Failed to rename", Response.Status.INTERNAL_ERROR)
    }

    private fun handleFsCopyFile(body: JsonObject): Response {
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val srcPath = body.get("src")?.asString ?: return jsonError("Missing src", Response.Status.BAD_REQUEST)
        val dstPath = body.get("dest")?.asString ?: return jsonError("Missing dest", Response.Status.BAD_REQUEST)

        val src = PathUtils.resolveVaultPath(vaultRoot, srcPath)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)
        val dst = PathUtils.resolveVaultPath(vaultRoot, dstPath)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsUnlink(params: Map<String, String>, body: JsonObject): Response {
        val pathParam = params["path"] ?: body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val vaultRoot = getVaultRoot(params, body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (resolved.exists()) {
            if (resolved.isDirectory) {
                return jsonError("EISDIR: illegal operation on a directory", Response.Status.BAD_REQUEST, "EISDIR")
            }
            resolved.delete()
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsRmdir(params: Map<String, String>, body: JsonObject): Response {
        val pathParam = params["path"] ?: body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val vaultRoot = getVaultRoot(params, body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (resolved.exists()) {
            if (!resolved.isDirectory) {
                return jsonError("ENOTDIR: not a directory", Response.Status.BAD_REQUEST, "ENOTDIR")
            }
            resolved.delete()
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsRm(params: Map<String, String>, body: JsonObject): Response {
        val pathParam = params["path"] ?: body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val recursive = params["recursive"] == "true" || body.get("recursive")?.asBoolean == true
        val vaultRoot = getVaultRoot(params, body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (resolved.exists()) {
            if (resolved.isDirectory) {
                if (recursive) resolved.deleteRecursively() else resolved.delete()
            } else {
                resolved.delete()
            }
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsAccess(params: Map<String, String>): Response {
        val pathParam = params["path"] ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (!resolved.exists()) {
            return jsonError("ENOENT: no such file or directory", Response.Status.NOT_FOUND, "ENOENT")
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsUtimes(body: JsonObject): Response {
        val pathParam = body.get("path")?.asString ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val resolved = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        val mtime = body.get("mtime")?.asLong ?: System.currentTimeMillis()
        if (resolved.exists()) {
            resolved.setLastModified(mtime)
        }
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleFsBatchRead(body: JsonObject): Response {
        val vaultRoot = getVaultRoot(emptyMap(), body) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathsArr = body.getAsJsonArray("paths") ?: JsonArray()

        val filesMap = mutableMapOf<String, String>()
        for (el in pathsArr) {
            val rel = el.asString
            val target = PathUtils.resolveVaultPath(vaultRoot, rel)
            if (target != null && target.exists() && target.isFile) {
                try {
                    filesMap[rel] = target.readText(Charsets.UTF_8)
                } catch (_: Exception) {}
            }
        }
        return jsonResponse(mapOf("files" to filesMap))
    }

    private fun handleFsTree(params: Map<String, String>): Response {
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val reqPath = params["path"]
        val rootDir = if (!reqPath.isNullOrBlank()) {
            PathUtils.resolveVaultPath(vaultRoot, reqPath) ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)
        } else vaultRoot

        val tree = walkTree(rootDir)
        return jsonResponse(tree)
    }

    private fun handleFsDownload(params: Map<String, String>): Response {
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = params["path"] ?: return jsonError("Missing path", Response.Status.BAD_REQUEST)
        val target = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (!target.exists() || target.isDirectory) {
            return jsonError("File not found", Response.Status.NOT_FOUND)
        }

        val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", FileInputStream(target), target.length())
        res.addHeader("Content-Disposition", "attachment; filename=\"${target.name}\"")
        return res
    }

    private fun handleFsDownloadZip(params: Map<String, String>): Response {
        val vaultRoot = getVaultRoot(params) ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)
        val pathParam = params["path"] ?: ""
        val target = PathUtils.resolveVaultPath(vaultRoot, pathParam)
            ?: return jsonError("Invalid path", Response.Status.FORBIDDEN)

        if (!target.exists() || !target.isDirectory) {
            return jsonError("Not a directory", Response.Status.BAD_REQUEST)
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(BufferedOutputStream(baos)).use { zos ->
            zipFolder(target, target.name, zos)
        }

        val zipBytes = baos.toByteArray()
        val res = newFixedLengthResponse(
            Response.Status.OK,
            "application/zip",
            ByteArrayInputStream(zipBytes),
            zipBytes.size.toLong()
        )
        res.addHeader("Content-Disposition", "attachment; filename=\"${target.name}.zip\"")
        return res
    }

    private fun zipFolder(folder: File, basePath: String, zos: ZipOutputStream) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            val entryPath = "$basePath/${file.name}"
            if (file.isDirectory) {
                zipFolder(file, entryPath, zos)
            } else {
                zos.putNextEntry(ZipEntry(entryPath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun handleVaultList(): Response {
        val list = vaultManager.listVaults().map {
            mapOf("id" to it.id, "name" to it.name, "path" to it.path)
        }
        return jsonResponse(list)
    }

    private fun handleVaultInfo(params: Map<String, String>): Response {
        val vaultId = params["vault"] ?: vaultManager.getDefaultVaultId()
        val vaultPath = vaultManager.getVaultPath(vaultId)
            ?: return jsonError("Vault not found", Response.Status.NOT_FOUND)

        val data = mapOf(
            "id" to vaultId,
            "name" to vaultId,
            "path" to vaultPath.absolutePath,
            "platform" to "linux",
            "version" to downloader.getInstalledVersion()
        )
        return jsonResponse(data)
    }

    private fun handleVaultCreate(body: JsonObject): Response {
        val name = body.get("name")?.asString?.trim() ?: ""
        if (name.isBlank()) {
            return jsonError("Invalid vault name", Response.Status.BAD_REQUEST)
        }
        val success = vaultManager.createVault(name)
        if (!success) {
            return jsonError("Failed to create vault", Response.Status.CONFLICT)
        }
        val vaultPath = vaultManager.getVaultPath(name)
        return jsonResponse(mapOf("ok" to true, "id" to name, "path" to vaultPath?.absolutePath))
    }

    private fun handleVaultRename(body: JsonObject): Response {
        val oldId = body.get("vault")?.asString ?: return jsonError("Missing vault", Response.Status.BAD_REQUEST)
        val newName = body.get("name")?.asString?.trim() ?: return jsonError("Missing new name", Response.Status.BAD_REQUEST)

        val success = vaultManager.renameVault(oldId, newName)
        if (!success) {
            return jsonError("Failed to rename vault", Response.Status.CONFLICT)
        }
        val vaultPath = vaultManager.getVaultPath(newName)
        return jsonResponse(mapOf("ok" to true, "id" to newName, "path" to vaultPath?.absolutePath))
    }

    private fun handleVaultRemove(params: Map<String, String>): Response {
        val vaultId = params["vault"] ?: return jsonError("Missing vault", Response.Status.BAD_REQUEST)
        val success = vaultManager.removeVault(vaultId)
        return if (success) jsonResponse(mapOf("ok" to true)) else jsonError("Failed to remove", Response.Status.NOT_FOUND)
    }

    /**
     * HTTP 跨域代理转发（Obsidian 插件与主题市场、在线同步等）
     */
    private fun handleProxy(body: JsonObject): Response {
        val url = body.get("url")?.asString ?: return jsonError("Missing url", Response.Status.BAD_REQUEST)
        val method = (body.get("method")?.asString ?: "GET").uppercase()
        val headersObj = body.getAsJsonObject("headers")

        var targetUrl = url
        if (targetUrl.contains("raw.githubusercontent.com/")) {
            val parts = targetUrl.substringAfter("raw.githubusercontent.com/").split("/")
            if (parts.size >= 4) {
                val user = parts[0]
                val repo = parts[1]
                val branch = parts[2]
                val filePath = parts.drop(3).joinToString("/")
                targetUrl = "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$filePath"
            }
        }

        val reqBuilder = Request.Builder().url(targetUrl)

        if (headersObj != null) {
            for ((key, value) in headersObj.entrySet()) {
                if (!key.equals("host", ignoreCase = true) && !key.equals("content-length", ignoreCase = true)) {
                    reqBuilder.header(key, value.asString)
                }
            }
        }

        if (method == "POST" || method == "PUT" || method == "PATCH") {
            val bodyContent = body.get("body")?.asString ?: ""
            reqBuilder.method(method, bodyContent.toRequestBody("application/json".toMediaTypeOrNull()))
        } else {
            reqBuilder.method(method, null)
        }

        return try {
            val callRes = okHttpClient.newCall(reqBuilder.build()).execute()
            val resBodyBytes = callRes.body?.bytes() ?: ByteArray(0)
            val isBinary = body.get("binary")?.asBoolean == true

            val resHeaders = mutableMapOf<String, String>()
            for ((hName, hVal) in callRes.headers) {
                resHeaders[hName] = hVal
            }

            val result = mapOf(
                "status" to callRes.code,
                "statusText" to callRes.message,
                "headers" to resHeaders,
                "body" to if (isBinary) Base64.encodeToString(resBodyBytes, Base64.NO_WRAP) else String(resBodyBytes, Charsets.UTF_8)
            )
            jsonResponse(result)
        } catch (e: Exception) {
            jsonError("Proxy request failed: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleVersion(): Response {
        return jsonResponse(mapOf(
            "version" to "1.0.0",
            "build" to "android-ignis",
            "obsidianVersion" to downloader.getInstalledVersion()
        ))
    }

    private fun handleSettings(method: Method, body: JsonObject): Response {
        if (method == Method.POST) {
            val editor = settingsPrefs.edit()
            for ((key, value) in body.entrySet()) {
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> editor.putLong(key, value.asLong)
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> editor.putBoolean(key, value.asBoolean)
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> editor.putString(key, value.asString)
                }
            }
            editor.apply()
        }

        val all = settingsPrefs.all
        val defaultSettings = mutableMapOf<String, Any>(
            "contentCacheBytes" to 104857600L,
            "inputCacheBytes" to 10485760L,
            "inputCacheTtlMs" to 30000L,
            "directFetchHosts" to emptyList<String>()
        )
        all.forEach { (k, v) ->
            if (v != null) {
                defaultSettings[k] = v
            }
        }
        return jsonResponse(defaultSettings)
    }

    private fun walkTree(rootDir: File): Map<String, Any> {
        val tree = mutableMapOf<String, Any>()
        walkRecursive(rootDir, "", tree)
        android.util.Log.i("IgnisHttpServer", "walkTree [${rootDir.absolutePath}] scanned ${tree.size} items (canRead=${rootDir.canRead()})")
        return tree
    }

    private fun walkRecursive(dir: File, prefix: String, tree: MutableMap<String, Any>) {
        val files = dir.listFiles()
        if (files == null) {
            android.util.Log.w("IgnisHttpServer", "listFiles returned null for [${dir.absolutePath}] - canRead=${dir.canRead()}, exists=${dir.exists()}")
            return
        }
        for (f in files) {
            val name = f.name
            // 忽略内部临时和版本控制垃圾目录，但保留 .obsidian 核心配置目录
            if (name == ".git" || name == ".trash" || name == ".DS_Store") {
                continue
            }
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (f.isDirectory) {
                tree[rel] = mapOf("type" to "directory")
                walkRecursive(f, rel, tree)
            } else {
                tree[rel] = mapOf(
                    "type" to "file",
                    "size" to f.length(),
                    "mtime" to f.lastModified(),
                    "ctime" to f.lastModified()
                )
            }
        }
    }

    private fun jsonResponse(obj: Any, status: Response.Status = Response.Status.OK): Response {
        val bytes = gson.toJson(obj).toByteArray(Charsets.UTF_8)
        val res = newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun jsonError(message: String, status: Response.Status, code: String? = null): Response {
        val map = mutableMapOf<String, Any>("error" to message)
        if (code != null) map["code"] = code
        val bytes = gson.toJson(map).toByteArray(Charsets.UTF_8)
        val res = newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
        res.addHeader("Cache-Control", "no-store")
        return res
    }
}
