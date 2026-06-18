#!/usr/bin/env bash
# ============================================================
# Claude Code 追踪数据清理脚本 - Windows Git Bash 版
# 适配自: https://github.com/win4r/cc-notebook
# 环境: Git Bash (MINGW64) on Windows
# ============================================================

set -e

CLAUDE_JSON="$USERPROFILE/.claude.json"
CLAUDE_DIR="$USERPROFILE/.claude"
BACKUP_DIR="$USERPROFILE/Desktop/claude-backup-$(date +%Y%m%d-%H%M%S)"

# ---- 工具检查 ----
check_python() {
    if command -v python3 &>/dev/null; then
        PYTHON=python3
    elif command -v python &>/dev/null; then
        PYTHON=python
    else
        echo "[错误] 需要 Python3，请先安装或使用 WSL 版本脚本"
        exit 1
    fi
}

# ---- 第一级: 重置设备标识 ----
reset_device_id() {
    echo "=== [1/5] 重置设备标识 ==="
    if [ ! -f "$CLAUDE_JSON" ]; then
        echo "  跳过: .claude.json 不存在"
        return
    fi

    # 查看当前状态
    echo "  当前追踪ID:"
    grep -E '"userID"|"anonymousId"|"firstStartTime"|"claudeCodeFirstTokenDate"' "$CLAUDE_JSON" || true

    # 删除追踪标识
    $PYTHON -c "
import json
p = r'$CLAUDE_JSON'
with open(p, 'r') as f:
    d = json.load(f)
removed = []
for k in ['userID', 'anonymousId', 'firstStartTime', 'claudeCodeFirstTokenDate']:
    if k in d:
        removed.append(k)
        del d[k]
with open(p, 'w') as f:
    json.dump(d, f, indent=2)
print(f'  已删除: {removed}')
print('  下次启动 Claude Code 会自动生成新的 userID')
"
}

# ---- 第二级: 清除遥测和分析数据 ----
clear_telemetry() {
    echo "=== [2/5] 清除遥测和分析数据 ==="
    rm -rf "$CLAUDE_DIR/telemetry/" 2>/dev/null && echo "  已删除: telemetry/" || echo "  跳过: telemetry/ (不存在)"
    rm -rf "$CLAUDE_DIR/statsig/" 2>/dev/null && echo "  已删除: statsig/" || echo "  跳过: statsig/ (不存在)"
    rm -f "$CLAUDE_DIR/stats-cache.json" 2>/dev/null && echo "  已删除: stats-cache.json" || echo "  跳过: stats-cache.json (不存在)"
}

# ---- 第三级: 清除会话和历史记录 ----
clear_sessions() {
    echo "=== [3/5] 清除会话和历史记录 ==="
    rm -f "$CLAUDE_DIR/history.jsonl" 2>/dev/null && echo "  已删除: history.jsonl" || echo "  跳过: history.jsonl"
    rm -rf "$CLAUDE_DIR/sessions/" 2>/dev/null && echo "  已删除: sessions/" || echo "  跳过: sessions/"
    rm -rf "$CLAUDE_DIR/paste-cache/" 2>/dev/null && echo "  已删除: paste-cache/" || echo "  跳过: paste-cache/"
    rm -rf "$CLAUDE_DIR/shell-snapshots/" 2>/dev/null && echo "  已删除: shell-snapshots/" || echo "  跳过: shell-snapshots/"
    rm -rf "$CLAUDE_DIR/session-env/" 2>/dev/null && echo "  已删除: session-env/" || echo "  跳过: session-env/"
    rm -rf "$CLAUDE_DIR/file-history/" 2>/dev/null && echo "  已删除: file-history/" || echo "  跳过: file-history/"
    rm -rf "$CLAUDE_DIR/debug/" 2>/dev/null && echo "  已删除: debug/" || echo "  跳过: debug/"
}

# ---- 第四级: 清除 OAuth 账号关联 ----
clear_oauth() {
    echo "=== [4/5] 清除 OAuth 账号关联 ==="
    if [ ! -f "$CLAUDE_JSON" ]; then
        echo "  跳过: .claude.json 不存在"
        return
    fi

    # 查看当前账号
    $PYTHON -c "
import json
p = r'$CLAUDE_JSON'
with open(p) as f:
    d = json.load(f)
oa = d.get('oauthAccount', {})
print(f\"  账号 UUID: {oa.get('accountUuid', '无')}\")
print(f\"  邮箱: {oa.get('emailAddress', '无')}\")
"

    # 清除 Windows 凭据管理器中的 Token
    cmd.exe /c "cmdkey /delete:claude-code" 2>/dev/null && echo "  已删除: Windows凭据 claude-code" || echo "  跳过: Windows凭据 claude-code (不存在)"
    cmd.exe /c "cmdkey /delete:claude-code-credentials" 2>/dev/null && echo "  已删除: Windows凭据 claude-code-credentials" || echo "  跳过: Windows凭据 claude-code-credentials (不存在)"

    # 清除配置文件中的账号缓存
    $PYTHON -c "
import json
p = r'$CLAUDE_JSON'
with open(p, 'r') as f:
    d = json.load(f)
removed = []
for k in ['oauthAccount', 's1mAccessCache', 'groveConfigCache',
          'passesEligibilityCache', 'clientDataCache',
          'cachedExtraUsageDisabledReason', 'githubRepoPaths']:
    if k in d:
        removed.append(k)
        del d[k]
with open(p, 'w') as f:
    json.dump(d, f, indent=2)
print(f'  已删除账号缓存: {removed}')
"
}

# ---- 第五级: 完全重置 (核弹选项) ----
full_reset() {
    echo "=== [5/5] 完全重置 ==="

    # 备份
    mkdir -p "$BACKUP_DIR"
    cp "$CLAUDE_DIR/CLAUDE.md" "$BACKUP_DIR/" 2>/dev/null && echo "  已备份: CLAUDE.md"
    cp "$CLAUDE_DIR/settings.json" "$BACKUP_DIR/" 2>/dev/null && echo "  已备份: settings.json"
    cp "$CLAUDE_DIR/settings.local.json" "$BACKUP_DIR/" 2>/dev/null && echo "  已备份: settings.local.json"
    cp "$CLAUDE_DIR/mcp.json" "$BACKUP_DIR/" 2>/dev/null && echo "  已备份: mcp.json"
    cp -r "$CLAUDE_DIR/skills" "$BACKUP_DIR/" 2>/dev/null && echo "  已备份: skills/"
    cp -r "$CLAUDE_DIR/hooks" "$BACKUP_DIR/" 2>/dev/null && echo "  已备份: hooks/"
    echo "  备份位置: $BACKUP_DIR"

    # 删除
    rm -rf "$CLAUDE_DIR/"
    rm -f "$CLAUDE_JSON"

    # 恢复关键配置
    mkdir -p "$CLAUDE_DIR"
    cp "$BACKUP_DIR/CLAUDE.md" "$CLAUDE_DIR/" 2>/dev/null
    cp "$BACKUP_DIR/settings.json" "$CLAUDE_DIR/" 2>/dev/null
    cp "$BACKUP_DIR/settings.local.json" "$CLAUDE_DIR/" 2>/dev/null
    cp "$BACKUP_DIR/mcp.json" "$CLAUDE_DIR/" 2>/dev/null
    [ -d "$BACKUP_DIR/skills" ] && cp -r "$BACKUP_DIR/skills" "$CLAUDE_DIR/"
    [ -d "$BACKUP_DIR/hooks" ] && cp -r "$BACKUP_DIR/hooks" "$CLAUDE_DIR/"

    cmd.exe /c "cmdkey /delete:claude-code" 2>/dev/null || true
    cmd.exe /c "cmdkey /delete:claude-code-credentials" 2>/dev/null || true

    echo "  已完全重置，配置已恢复。"
}

# ---- 主流程 ----
check_python

echo ""
echo "============================================"
echo "  Claude Code 追踪数据清理 (Windows Git Bash)"
echo "  时间: $(date)"
echo "============================================"
echo ""

case "${1:-}" in
    device)
        reset_device_id
        ;;
    telemetry)
        clear_telemetry
        ;;
    sessions)
        clear_sessions
        ;;
    oauth)
        clear_oauth
        ;;
    full|nuke)
        full_reset
        ;;
    all|*)
        reset_device_id
        echo ""
        clear_telemetry
        echo ""
        clear_sessions
        echo ""
        clear_oauth
        echo ""
        echo "============================================"
        echo "  1-4级清理完成 (保留配置)"
        echo "  如需完全重置，执行: bash $0 full"
        echo "============================================"
        ;;
esac
