#!/usr/bin/env bash
# ============================================================
# 一键清理脚本: 同时清理 Windows + WSL 两边 Claude Code 追踪数据
# 用法:
#   bash cleanup-all.sh          # 1-4级清理 (保留配置)
#   bash cleanup-all.sh full     # 5级完全重置
#   bash cleanup-all.sh status   # 仅查看当前追踪状态
# ============================================================

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║  Claude Code 双环境追踪数据清理          ║"
echo "║  Windows Git Bash + WSL Ubuntu           ║"
echo "╚══════════════════════════════════════════╝"
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

show_status() {
    echo "--- Windows Git Bash ---"
    echo "  路径: $USERPROFILE/.claude.json"
    if [ -f "$USERPROFILE/.claude.json" ]; then
        grep -oE '"(userID|anonymousId|firstStartTime|oauthAccount)"[^}]*' "$USERPROFILE/.claude.json" | head -4 || echo "  (无法读取)"
    fi
    echo "  .claude/ 目录:"
    for d in telemetry statsig history.jsonl sessions paste-cache shell-snapshots session-env file-history debug; do
        if [ -e "$USERPROFILE/.claude/$d" ]; then
            SIZE=$(du -sh "$USERPROFILE/.claude/$d" 2>/dev/null | cut -f1)
            echo "    $d ($SIZE)"
        fi
    done

    echo ""
    echo "--- WSL Ubuntu ---"
    echo "  路径: /root/.claude.json"
    wsl -d Ubuntu-22.04 -- bash -c '
        if [ -f /root/.claude.json ]; then
            grep -oE "(userID|anonymousId|firstStartTime|oauthAccount)[^}]*" /root/.claude.json | head -4
        fi
        echo "  .claude/ 目录:"
        for d in telemetry statsig history.jsonl sessions paste-cache shell-snapshots session-env file-history debug stats-cache.json; do
            if [ -e /root/.claude/$d ]; then
                SIZE=$(du -sh /root/.claude/$d 2>/dev/null | cut -f1)
                echo "    $d ($SIZE)"
            fi
        done
    '
}

if [ "${1:-}" = "status" ]; then
    show_status
    exit 0
fi

# 清理 Windows 侧
echo ">>> 清理 Windows Git Bash 环境..."
bash "$SCRIPT_DIR/claude-cleanup-win.sh" "${1:-all}"
echo ""

# 清理 WSL 侧
echo ">>> 清理 WSL Ubuntu 环境..."
wsl -d Ubuntu-22.04 -- bash -c "bash /mnt/d/study/bug-agent-platform/scripts/claude-cleanup-wsl.sh ${1:-all}"
echo ""

echo "╔══════════════════════════════════════════╗"
echo "║  两边环境清理完成                         ║"
echo "║  运行 'bash cleanup-all.sh status' 查看结果 ║"
echo "╚══════════════════════════════════════════╝"
