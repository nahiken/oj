#!/bin/sh
# 容器内存监控：每 interval 秒打印一次 cgroup 内存用量，收到 "q" 行后退出。
#
# 只用 POSIX sh 语法。目标镜像可以是任意带 javac 的 JDK 镜像，/bin/sh 未必是 bash
# （Debian 系的 jenkins/agent 是 dash，dash 的 read 不支持 -n，旧版脚本在容器里
# 每个循环都报 "read: Illegal option -n"，内存永远读成 0）。

if [ -z "$1" ]; then
  echo "Usage: $0 <interval_in_seconds>" >&2
  exit 1
fi

INTERVAL="$1"
MEMORY_FILE="/sys/fs/cgroup/memory.current"

if [ ! -f "$MEMORY_FILE" ]; then
  echo "Error: $MEMORY_FILE does not exist." >&2
  exit 1
fi

monitor() {
  while true; do
    cat "$MEMORY_FILE"
    sleep "$INTERVAL"
  done
}

monitor &
MONITOR_PID=$!

# 读到 "q" 行或 stdin 关闭（父进程写完就 close）都退出
while IFS= read -r line; do
  case "$line" in
    q | Q) break ;;
  esac
done

kill "$MONITOR_PID" 2>/dev/null
wait "$MONITOR_PID" 2>/dev/null
exit 0
