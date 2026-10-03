#!/bin/sh
#
# QQ 猫娘 · 输入法桥配置工具
#
# 路线 B（LSPosed 模块）的配置管理。配置文件由输入法进程在
# /data/user/0/com.tencent.wetype/qqime/ 下读写，root 通过本脚本操作。
#
# 配置文件（首次运行自动释放）：
#   katxt      颜文字词库（默认 54 条）
#   whitelist  包名白名单（默认 QQ / TIM）
#
# 用法: qqime.sh <命令>
#   wl              查看白名单
#   wl add <包名>    添加（如 com.tencent.mm 微信）
#   wl del <包名>    删除
#   wl set a b c    整体覆盖
#   katxt           查看词库条数
#   log [n]         查看运行日志
#   reload          修正文件属主（输入法要有写权限）
#
set -u

DIR=/data/user/0/com.tencent.wetype/qqime
WL="$DIR/whitelist"
KAO="$DIR/katxt"
LOG="$DIR/qqime.log"
die() { printf 'qqime: %s\n' "$*" >&2; exit 1; }

need_root() {
    [ "$(id -u)" = 0 ] || die "需要 root 权限运行"
}

# 自动探测微信输入法的 uid（不写死，换设备也能用）
ime_uid() {
    u=$(stat -c %u "$DIR" 2>/dev/null)
    case "$u" in ''|*[!0-9]*) echo 10361 ;; *) echo "$u" ;; esac
}

fix_owner() {
    [ -e "$1" ] || return 0
    u=$(ime_uid)
    chown "$u:$u" "$1" 2>/dev/null || true
    chmod 600 "$1" 2>/dev/null || true
}

case "${1:-wl}" in
    wl)
        sub="${2:-list}"
        case "$sub" in
            list|'')
                printf '白名单：%s\n' "$WL"
                [ -f "$WL" ] && sed 's/^/  /' "$WL" || printf '  (文件不存在，重启输入法会自动释放默认值)\n'
                ;;
            add)
                need_root
                pkg="${3:-}"
                [ -n "$pkg" ] || die '用法: qqime.sh wl add <包名>'
                [ -f "$WL" ] || die "白名单文件不存在，请先在输入框里按一次键以触发释放"
                if grep -qxF "$pkg" "$WL"; then
                    printf '已存在：%s\n' "$pkg"
                else
                    printf '%s\n' "$pkg" >> "$WL"
                    fix_owner "$WL"
                    printf '已添加：%s（立即生效，无需重启）\n' "$pkg"
                fi
                ;;
            del|rm)
                need_root
                pkg="${3:-}"
                [ -n "$pkg" ] || die '用法: qqime.sh wl del <包名>'
                [ -f "$WL" ] || die "白名单文件不存在"
                grep -vxF "$pkg" "$WL" > "$WL.tmp" && mv "$WL.tmp" "$WL"
                fix_owner "$WL"
                printf '已删除：%s\n' "$pkg"
                ;;
            set)
                need_root
                shift 2
                [ $# -gt 0 ] || die '用法: qqime.sh wl set <包名> [包名...]'
                printf '%s\n' "$@" > "$WL"
                fix_owner "$WL"
                printf '已写入 %s 个包名\n' "$#"
                ;;
            *)
                die "未知子命令: $sub（list/add/del/set）"
                ;;
        esac
        ;;
    katxt)
        if [ -f "$KAO" ]; then
            printf '词库：%s（%s 条）\n' "$KAO" "$(grep -c . "$KAO")"
        else
            printf '词库文件不存在，重启输入法会自动释放内置 54 条\n'
        fi
        ;;
    log)
        n="${2:-30}"
        [ -f "$LOG" ] && tail -n "$n" "$LOG" || printf '暂无日志（模块可能未加载）\n'
        ;;
    reload)
        need_root
        for f in "$WL" "$KAO" "$LOG"; do
            fix_owner "$f"
        done
        printf '已修正文件属主为 %s\n' "$(ime_uid)"
        ;;
    *)
        sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
        ;;
esac
