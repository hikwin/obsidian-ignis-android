# OBServer

## 项目概述

1. 基于开源项目 **Ignis** 衍生并重构的 Android 独立原生实现。项目初衷是为了实现在局域网内无需在其它设备（电脑、平板或手机）上安装 Obsidian，即可直接通过浏览器更快捷地访问笔记。
2. 无需 PHP、Node.js 或外部服务器，在 Android 本地设备即可完整运行 Obsidian Web 系统，并支持带访问密码的局域网设备跨端访问。
3. 本质不是为了替代坚如磐石的 Obsidian 客户端，而是为了让 Obsidian 笔记随处可用。

---

## 核心特性

1. **纯 Android 原生实现（替代 PHP 后端）**：
   - 内置轻量、低功耗的嵌入式 HTTP Web 服务引擎（基于 NanoHTTPD + Kotlin 实现）。
   - 完全实现了原 PHP 系统（[obsidian-ignis-php](https://github.com/hikwin/obsidian-ignis-php)）的全部 RESTful API 与静态文件分发：
     - `/api/bootstrap`：环境初始化与知识库树形目录索引
     - `/api/fs/*`：完整模拟 Node.js 文件系统接口（`stat`, `readdir`, `readFile`, `writeFile`, `appendFile`, `mkdir`, `rename`, `copyFile`, `unlink`, `rm`, `utimes`, `batch-read`, `tree`, `download`, `download-zip`）
     - `/api/vault/*`：多知识库管理（创建、重命名、删除、查询）
     - `/api/proxy`：网络反向代理与跨域穿透（支持 Obsidian 插件和主题市场直接访问）
     - `/vault-files/*`：静态媒体与附件支持（图片、PDF、音视频等流媒体分发）

2. **官方核心一键下载与自动 ASAR 解压**：
   - 遵守 Obsidian 官方 EULA 许可协议，未内置打包官方专有文件，保持 APK 纯净小巧（约 5.5MB）。
   - 用户在应用内点击“下载 / 更新核心”，系统将从 GitHub Releases 官方下载 `obsidian-x.x.x.asar.gz`。
   - 提供 GitHub 加速镜像节点（ghfast / ghproxy）供国内网络快速下载。
   - 原生高性能解压引擎（Gzip 流式解压 + ASAR 归档文件提取），全自动部署至专属存储目录。

3. **启动 Web 本地服务，支持局域网跨端访问与安全访问码**：
   - 可以在 Android 中自由开启 / 停止本地 Web 服务，支持自定义端口（默认 8080）。
   - **安全访问码防护**：支持配置网页服务访问码。设置访问码后，外部浏览器打开网址需验证访问码方可进入知识库；留空则直接免密访问。
   - 自动获取并展示本机局域网 IP（如 `http://192.168.x.x:8080/`），同一 Wi-Fi 下的电脑、平板、其他手机可通过浏览器无缝访问。
   - 配置 Android 前台服务（Foreground Service）与持久通知栏，锁屏与后台依然保持服务在线。

4. **内置全屏原生浏览体验（WebView）**：
   - 在应用内点击“在应用内打开 Obsidian”，直接进入内置 WebView 全屏界面，享受接近桌面客户端的原生级体验。
   - 适配 Android 返回键导航与文件上传选择器。

---

## 核心架构目录

```text
app/src/main/
├── assets/
│   └── web/                     # 预置 Ignis 前端 UI 脚本与环境 shim 适配器
│       ├── ui/ignis-ui.js       # Ignis 前端 UI
│       ├── shim/shim-loader.js  # 浏览器 API 拦截与 Node.js 垫片
│       └── server/index.html    # 基础宿主模板页面
├── java/com/ignis/obsidian/
│   ├── MainActivity.kt          # 核心控制台仪表盘 Activity
│   ├── server/
│   │   ├── IgnisHttpServer.kt   # Android 嵌入式 HTTP 服务器（全面替代 PHP）
│   │   ├── IgnisServerService.kt# 前台持久服务与通知栏管理
│   │   └── VaultManager.kt      # Markdown 知识库与本地存储管理
│   ├── ui/
│   │   └── ObsidianWebViewActivity.kt # 内置全屏浏览 Activity
│   └── util/
│       ├── AsarExtractor.kt     # 纯 Kotlin 实现的 ASAR / GZ 归档解析提取器
│       ├── ObsidianDownloader.kt# 核心下载器（支持分片、进度与加速镜像）
│       ├── NetworkUtils.kt      # 局域网 IP 与网络工具
│       ├── PathUtils.kt         # 安全路径解析与防遍历防护
│       └── MimeUtils.kt         # MIME 类型解析器
└── res/                         # UI 布局、暗黑模式与 Obsidian 主题配色资源
```

---

## 开源许可证 (License)

本项目基于 [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE) 开源，并继承声明如下：

1. 本项目包含了源自 Nystik 的 [Ignis](https://github.com/Nystik-gh/ignis) 的修改组件。
2. Android 应用程序源代码（`app/`）、嵌入式 HTTP 服务器、管理工具及前端适配器补丁（如 `shim-loader.js`、`ignis-ui.js` 补丁）均在 **AGPL-3.0** 许可证下发布。
3. 本许可证**不适用于**任何专有的 Obsidian 客户端核心资源（如 `app.asar`、`asar.gz` 等），这些资源版权仍归属于 Dynalist Inc.，受其官方 EULA 最终用户许可协议约束。
