# HermesVoice — 斐讯 R1 智能语音助手

为斐讯 R1 智能音箱打造的离线语音交互系统，支持唤醒词检测、语音识别、AI 对话、语音合成的完整语音流水线。

## ✨ 功能

- 🎤 **本地唤醒词** — 基于 sherpa-onnx 的 KWS，支持任意中文唤醒词（如"小薇"），无需联网
- 🗣️ **语音识别** — faster-whisper 本地 STT，中文识别准确
- 🤖 **AI 对话** — 连接 Hermes Studio，支持多轮对话
- 🔊 **语音合成** — Edge TTS (XiaoxiaoNeural)，免费无需 API Key
- 🔄 **连续对话** — 说完即听，无需重复唤醒
- 🌐 **Web 配置面板** — 手机浏览器修改配置，中/英文切换
- ⚡ **开机自启** — BootReceiver 自动启动
- 🛠️ **无需 Android Studio** — 纯命令行构建 (aapt + javac + d8)

## 📋 系统要求

| 组件 | 要求 |
|------|------|
| R1 固件 | Android 5.1.1 (Rockchip RK3229) |
| 构建环境 | JDK 8 + Android SDK (build-tools 34.0.0, platform 22) |
| STT 服务器 | Mac/PC 上运行 faster-whisper (port 9000) |
| Hermes Studio | 已部署并可访问 (port 8748) |
| 网络 | R1 ↔ Mac/PC 需在同一局域网 |

## 🏗️ 项目结构

```
HermesVoice/
├── src/
│   ├── com/hermes/r1voice/
│   │   ├── MainActivity.java      # 主逻辑：状态机 + KWS + STT + Chat + TTS
│   │   ├── MiniHttpServer.java    # 轻量 HTTP 服务器 (port 6060)
│   │   ├── BootReceiver.java      # 开机自启广播接收器
│   │   └── ChinesePinyin.java     # 中文→带声调拼音 token 转换
│   └── com/k2fsa/sherpa/onnx/     # sherpa-onnx KWS JNI wrapper (7个类)
├── res/
│   ├── raw/
│   │   ├── pinyin.txt             # 20992条 中文→拼音 token 映射表
│   │   └── r1_control.html        # Web 控制面板 HTML
│   ├── layout/activity_main.xml
│   └── values/strings.xml
├── tools/
│   ├── stt_server.py              # faster-whisper STT 服务器
│   └── com.hermes.stt-server.plist # macOS launchd 自动重启配置
├── config.properties.example      # 配置文件模板
├── build.sh                       # 构建脚本
├── AndroidManifest.xml
└── debug.keystore                 # 调试签名
```

## 🚀 快速开始

### 1. 下载依赖

```bash
# sherpa-onnx KWS 模型
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
tar xvf sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
# 将 encoder/decoder/joiner .onnx + tokens.txt 推送到 R1:
# adb push encoder-*.onnx decoder-*.onnx joiner-*.onnx tokens.txt /mnt/internal_sd/sherpa-onnx-kws/

# sherpa-onnx JNI 库
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.15/sherpa-onnx-1.12.15-android.tar.bz2
# 解压后将 lib/armeabi-v7a/ 下的 .so 文件设置 SHERPA_LIB 路径

# faster-whisper STT
pip install faster-whisper
```

### 2. 配置

```bash
cp config.properties.example config.properties
# 编辑 config.properties，填入你的 Hermes Studio 地址和账号
```

### 3. 构建

```bash
chmod +x build.sh
export ANDROID_SDK=/path/to/your/android-sdk
export JAVA8_HOME=/path/to/jdk8
export JAVA11_HOME=/path/to/jdk11
./build.sh
```

### 4. 部署

```bash
# 启动 STT 服务器
python3 tools/stt_server.py

# 安装到 R1
adb connect 192.168.2.232:5555
adb push build_output/output/HermesVoice.apk /data/local/tmp/
adb shell "/system/bin/pm install -r /data/local/tmp/HermesVoice.apk"

# 推送配置
adb push config.properties /sdcard/hermes_config.properties

# 启动
adb shell "am start -n com.hermes.r1voice/.MainActivity"
```

### 5. 使用

1. 对 R1 说 **"小薇"**（或你设置的唤醒词）
2. 听到 **"在"** 的应答后开始说话
3. AI 回答会自动语音播放
4. 连续对话模式下，播放完会自动继续监听
5. 说 **"退下"** 或 **"再见"** 结束对话

**Web 配置面板**: 浏览器打开 `http://<R1_IP>:6060`

## 🔧 技术架构

```
┌──────────────┐
│   用户说话    │
└──────┬───────┘
       ▼
┌──────────────┐    本地离线     ┌──────────────────┐
│ KWS 唤醒检测  │ ◄──────────── │ sherpa-onnx 3.3M  │
│ (小薇)       │               │ zipformer-wenetspeech │
└──────┬───────┘               └──────────────────┘
       ▼
┌──────────────┐    本地离线     ┌──────────────────┐
│ STT 语音识别  │ ◄──────────── │ faster-whisper     │
│              │               │ (Mac/PC port 9000) │
└──────┬───────┘               └──────────────────┘
       ▼
┌──────────────┐    HTTP API    ┌──────────────────┐
│ Chat AI 对话  │ ◄──────────── │ Hermes Studio      │
│              │               │ (port 8748)        │
└──────┬───────┘               └──────────────────┘
       ▼
┌──────────────┐    HTTP API    ┌──────────────────┐
│ TTS 语音合成  │ ◄──────────── │ Edge TTS           │
│              │               │ (XiaoxiaoNeural)   │
└──────┬───────┘               └──────────────────┘
       ▼
┌──────────────┐
│ AudioTrack    │  MediaCodec 硬解码 MP3→PCM
│ 播放语音      │
└──────────────┘
```

## ⚠️ 已知限制

- **KWS 唤醒词必须是中文** — 模型使用中文拼音 token 体系
- **Edge TTS 输出 MPEG 2.5 格式** — 已用 MediaCodec 硬解码解决 MediaPlayer 兼容性
- **R1 无 root** — `pm disable` 不可用，Pandora/Unisound 用 `pm uninstall -k --user 0` 移除
- **STT 服务器需保持运行** — 建议用 launchd/systemd 自动重启
- **快速说话 KWS 可能漏检** — 3.3M 小模型有固有限制

## 📄 License

MIT License
