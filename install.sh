#!/bin/bash
# ============================================================
#  HermesVoice — 一键安装脚本
#  用法: ./install.sh <R1_IP>
#  示例: ./install.sh 192.168.1.100
# ============================================================
set -e

R1_IP="${1:-}"
if [ -z "$R1_IP" ]; then
    echo "❌ 请提供 R1 的 IP 地址"
    echo "用法: ./install.sh <R1_IP>"
    echo "示例: ./install.sh 192.168.1.100"
    exit 1
fi

R1_PORT="${R1_IP}:5555"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$SCRIPT_DIR/releases/HermesVoice-v1.0.apk"
CONFIG="$SCRIPT_DIR/config.properties"

echo "=========================================="
echo "  HermesVoice 安装脚本"
echo "=========================================="
echo "R1 IP: $R1_IP"
echo ""

# 检查 APK
if [ ! -f "$APK" ]; then
    echo "❌ APK 未找到: $APK"
    echo "请从 GitHub Releases 下载 HermesVoice-v1.0.apk"
    exit 1
fi

# 检查 adb
if ! command -v adb &>/dev/null; then
    echo "❌ adb 未安装"
    echo "请先安装 Android SDK Platform Tools:"
    echo "  macOS: brew install android-platform-tools"
    echo "  Linux: sudo apt install adb"
    exit 1
fi

# 连接 R1
echo "📡 连接 R1 ($R1_PORT)..."
adb connect "$R1_PORT" 2>&1
sleep 2

# 检查连接
if ! adb -s "$R1_PORT" shell echo ok &>/dev/null; then
    echo "❌ 无法连接到 R1，请检查:"
    echo "  1. R1 已开机且在同一局域网"
    echo "  2. ADB 已开启 (设置 → 关于 → 连续点击版本号)"
    exit 1
fi
echo "✅ 已连接 R1"

# 卸载旧版本（如果存在）
echo ""
echo "🗑️  卸载旧版本..."
adb -s "$R1_PORT" shell "/system/bin/pm uninstall com.hermes.r1voice" 2>/dev/null || true

# 安装
echo ""
echo "📦 安装 HermesVoice..."
adb -s "$R1_PORT" push "$APK" /data/local/tmp/HermesVoice.apk 2>&1
adb -s "$R1_PORT" shell "/system/bin/pm install /data/local/tmp/HermesVoice.apk" 2>&1
echo "✅ 安装完成"

# 推送配置（如果存在）
if [ -f "$CONFIG" ]; then
    echo ""
    echo "⚙️  推送配置文件..."
    adb -s "$R1_PORT" push "$CONFIG" /sdcard/hermes_config.properties 2>&1
    echo "✅ 配置已推送"
else
    echo ""
    echo "⚠️  未找到 config.properties"
    echo "请复制 config.properties.example 为 config.properties，填入你的配置后重新运行"
    echo "或通过 Web 配置面板 (http://$R1_IP:6060) 在线修改"
fi

# 启动
echo ""
echo "🚀 启动 HermesVoice..."
adb -s "$R1_PORT" shell "am start -n com.hermes.r1voice/.MainActivity" 2>&1
sleep 3

# 验证
if adb -s "$R1_PORT" shell "ps | grep hermes" | grep -q hermes; then
    echo ""
    echo "=========================================="
    echo "  ✅ 安装成功！"
    echo "=========================================="
    echo ""
    echo "使用方法:"
    echo "  1. 对 R1 说 \"小薇\"（或你设置的唤醒词）"
    echo "  2. 听到 \"在\" 后开始说话"
    echo "  3. 说 \"退下\" 结束对话"
    echo ""
    echo "Web 配置面板: http://$R1_IP:6060"
    echo ""
else
    echo "❌ 启动失败，请检查 R1 日志"
    adb -s "$R1_PORT" shell "cat /sdcard/hermes_voice.log" 2>&1 | tail -10
fi
