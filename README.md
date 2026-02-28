# unNCM for Android

一个基于 Kotlin 的原生 Android 工具，用于：

- 解密网易云音乐 `.ncm` 文件为常见音频格式（如 `mp3` / `flac`）
- 为音频文件补全元数据（标题 / 艺术家 / 专辑）
- 可选补全歌词与封面

> UI 风格：深色 Tech 风，Material 3（支持动态配色）

---

## 功能特性

- **NCM 解密**：解析 NCM 头信息并流式解密音频内容
- **元数据增强**：自动查询网易云接口并写入标签、歌词、封面
- **双入口导入**：
  - 选择文件夹（SAF）
  - 直接多选文件（SAF）
- **并发处理**：支持 `1~8` 线程转换
- **会话恢复**：持久化上次选择的目录/待处理文件（含 URI 持久权限）
- **自动输出目录**：
  - 文件夹模式：输出到 `输入目录/unlocked`
  - 多选文件模式：输出到 `Android/data/<package>/files/unlocked`

---

## 使用流程

1. 点击 **选择 NCM 文件** 或 **选择文件夹**
2. 等待扫描完成（状态栏会显示进度状态）
3. 调整线程数（`Threads` 滑块）
4. 点击 **开始转换**
5. 查看输出目录中的结果文件

---

## 技术栈

- **Kotlin** + **Android ViewBinding**
- **MVVM**（`MainActivity` + `MainViewModel`）
- **Coroutines / StateFlow / LiveData**
- **jAudioTagger**（音频标签读写）
- **OkHttp**（网易云接口请求）
- **DocumentFile / SAF**（Android 分区存储文件访问）

---

## 项目结构

```text
app/src/main/java/top/xihale/unncm/
├── MainActivity.kt                 # UI 交互、文件选择、状态恢复
├── MainViewModel.kt                # 扫描与并发转换调度
├── FileConverter.kt                # 单文件处理总入口（NCM / 普通音频）
├── NcmDecryptor.kt                 # NCM 格式解析与解密
├── AudioMetadataProcessor.kt       # 标签/歌词/封面写入
├── NeteaseApiService.kt            # 网易云搜索、歌词、封面 API
├── MediaMetadataRetrieverHelper.kt # 轻量元数据检测
├── MusicMetadata.kt
├── FileAdapter.kt
└── utils/
   ├── FastScanner.kt               # SAF 流式扫描
   ├── CryptoUtils.kt
   └── Logger.kt
```

---

## 本地开发

### 环境要求

- Android Studio（建议最新稳定版）
- Android SDK 34
- JDK 17（与 AGP 8.13.x 更匹配）

### 构建 Debug APK

```bash
./gradlew :app:assembleDebug
```

APK 默认输出：

```text
app/build/outputs/apk/debug/
```

---

## 发布与版本

项目使用 Git Tag 自动发布（GitHub Actions）：

- 工作流：`.github/workflows/release-on-tag.yml`
- 触发规则：push tag `v*`
- **Tag 格式要求**：

```text
vMAJ.MIN.PATCH-(alpha|beta|rc|release).N
```

示例：

- `v1.4.0-alpha.1`
- `v1.4.0-release.0`

### Release 必需 Secrets

- `UNNCM_RELEASE_STORE_FILE_BASE64`：签名 `.jks` 的 base64 内容
- `UNNCM_RELEASE_STORE_PASSWORD`
- `UNNCM_RELEASE_KEY_ALIAS`
- `UNNCM_RELEASE_KEY_PASSWORD`

---

## 注意事项 / 当前限制

- 扫描逻辑目前基于 SAF 子项查询，**默认只扫描所选目录当前层级**（不递归深层子目录）
- 元数据增强依赖网络接口，请确保联网
- 歌曲匹配策略是“关键词搜索首条结果”，极少数歌曲可能匹配不准

---

## 合规声明

本项目仅用于学习与个人用途，请遵守当地法律法规及平台服务条款，尊重音乐版权。

---

## License

[MIT](./LICENSE)
