#!/usr/bin/env bash
# 融光 - 源码本地开发启动脚本
# 用法:
#   ./scripts/dev.sh          # 启动全部服务
#   ./scripts/dev.sh start    # 同上
#   ./scripts/dev.sh stop     # 停止前后端（保留 MySQL/Redis）
#   ./scripts/dev.sh stop --all  # 停止全部（含中间件）
#   ./scripts/dev.sh status   # 查看运行状态
#   ./scripts/dev.sh logs     # 跟踪日志（backend / frontend / all）

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/ai-fusion-video"
FRONTEND_DIR="$ROOT_DIR/ai-fusion-video-web"
COMPOSE_FILE="$BACKEND_DIR/docker-compose-middleware.yml"
RUNTIME_DIR="$ROOT_DIR/.dev"
LOG_DIR="$RUNTIME_DIR/logs"
PID_DIR="$RUNTIME_DIR/pids"

BACKEND_PORT=18080
FRONTEND_PORT=3000
MYSQL_PORT=43306
REDIS_PORT=46379

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${BLUE}[dev]${NC} $*"; }
ok()    { echo -e "${GREEN}[dev]${NC} $*"; }
warn()  { echo -e "${YELLOW}[dev]${NC} $*"; }
error() { echo -e "${RED}[dev]${NC} $*" >&2; }

mkdir -p "$LOG_DIR" "$PID_DIR"

docker_compose() {
  if docker compose version &>/dev/null; then
    docker compose "$@"
  elif command -v docker-compose &>/dev/null; then
    docker-compose "$@"
  else
    error "未找到 docker compose，请先安装 Docker"
    exit 1
  fi
}

check_command() {
  local cmd=$1 msg=$2
  if ! command -v "$cmd" &>/dev/null; then
    error "$msg"
    exit 1
  fi
}

setup_java() {
  local candidate java_bin version_raw major

  try_java() {
    local java_path=$1
    [[ -x "$java_path" ]] || return 1
    version_raw=$("$java_path" -version 2>&1 | head -n1) || return 1
    [[ "$version_raw" == *"Unable to locate a Java Runtime"* ]] && return 1
    major=$(sed -nE 's/.*version "([0-9]+).*/\1/p' <<<"$version_raw")
    [[ "$major" =~ ^[0-9]+$ ]] || return 1
    [[ "$major" -ge 21 ]] || return 1
    java_bin="$java_path"
    return 0
  }

  if [[ -n "${JAVA_HOME:-}" ]] && try_java "$JAVA_HOME/bin/java"; then
    :
  elif command -v java &>/dev/null && try_java "$(command -v java)"; then
    :
  else
    for candidate in \
      /opt/homebrew/opt/openjdk@21/bin/java \
      /opt/homebrew/opt/openjdk@26/bin/java \
      /opt/homebrew/opt/openjdk/bin/java \
      /usr/local/opt/openjdk@21/bin/java \
      /usr/local/opt/openjdk/bin/java; do
      try_java "$candidate" && break
    done
  fi

  if [[ -z "${java_bin:-}" ]] && [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
    local java_home
    java_home=$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)
    if [[ -n "$java_home" ]]; then
      try_java "$java_home/bin/java" || true
    fi
  fi

  if [[ -z "${java_bin:-}" ]]; then
    error "未找到可用的 JDK 21+"
    echo
    echo "  当前 JAVA_HOME=${JAVA_HOME:-未设置}"
    echo "  系统 java: $(command -v java 2>/dev/null || echo 未找到)"
    echo
    echo "  macOS 可执行以下命令安装："
    echo "    brew install openjdk@21"
    echo "    export JAVA_HOME=\"\$(/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)\""
    echo "    export PATH=\"\$JAVA_HOME/bin:\$PATH\""
    exit 1
  fi

  export JAVA_HOME="${java_bin%/bin/java}"
  export PATH="$JAVA_HOME/bin:$PATH"
  ok "使用 Java $major ($JAVA_HOME)"
}

check_node() {
  check_command node "未找到 Node.js，请安装 Node.js 20+"
  local major
  major=$(node -p "process.versions.node.split('.')[0]")
  if [[ "$major" -lt 20 ]]; then
    error "需要 Node.js 20+，当前版本: $(node -v)"
    exit 1
  fi
}

ensure_pnpm() {
  if command -v pnpm &>/dev/null; then
    return
  fi
  if command -v corepack &>/dev/null; then
    info "启用 corepack 并安装 pnpm..."
    corepack enable
    corepack prepare pnpm@10.32.1 --activate
    return
  fi
  error "未找到 pnpm，请执行: npm install -g pnpm"
  exit 1
}

is_port_open() {
  local host=$1 port=$2
  nc -z "$host" "$port" 2>/dev/null
}

wait_for_port() {
  local host=$1 port=$2 name=$3 timeout=${4:-90}
  local elapsed=0
  info "等待 $name 就绪 ($host:$port)..."
  while ! is_port_open "$host" "$port"; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [[ $elapsed -ge $timeout ]]; then
      error "$name 在 ${timeout}s 内未就绪"
      return 1
    fi
  done
  ok "$name 已就绪"
}

read_pid() {
  local file=$1
  if [[ -f "$file" ]]; then
    cat "$file"
  fi
}

is_pid_running() {
  local pid=$1
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

kill_pid_tree() {
  local pid=$1
  [[ -z "$pid" ]] && return
  if is_pid_running "$pid"; then
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    sleep 1
    if is_pid_running "$pid"; then
      pkill -KILL -P "$pid" 2>/dev/null || true
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
}

kill_port() {
  local port=$1
  local pids
  pids=$(lsof -ti:"$port" 2>/dev/null || true)
  if [[ -n "$pids" ]]; then
    echo "$pids" | xargs kill -TERM 2>/dev/null || true
    sleep 1
    pids=$(lsof -ti:"$port" 2>/dev/null || true)
    [[ -n "$pids" ]] && echo "$pids" | xargs kill -KILL 2>/dev/null || true
  fi
}

ensure_docker() {
  if docker info &>/dev/null; then
    return
  fi

  warn "Docker daemon 未运行，正在尝试启动 Docker Desktop..."
  open -a Docker 2>/dev/null || open -a "Docker Desktop" 2>/dev/null || true

  local elapsed=0
  while ! docker info &>/dev/null; do
    sleep 2
    elapsed=$((elapsed + 2))
    if (( elapsed % 10 == 0 )); then
      info "仍在等待 Docker 就绪... (${elapsed}s)"
    fi
    if [[ $elapsed -ge 120 ]]; then
      error "Docker 未能在 120s 内启动"
      echo
      echo "  请手动操作："
      echo "  1. 打开 Docker Desktop，等待状态变为 Running"
      echo "  2. 再执行: ./scripts/dev.sh"
      echo
      exit 1
    fi
  done
  ok "Docker 已就绪"
}

start_middleware() {
  ensure_docker
  info "启动 MySQL 和 Redis..."
  if ! docker_compose -f "$COMPOSE_FILE" up -d; then
    error "中间件启动失败"
    echo "  请确认 Docker Desktop 已完全启动后重试"
    exit 1
  fi
  wait_for_port localhost "$MYSQL_PORT" "MySQL"
  wait_for_port localhost "$REDIS_PORT" "Redis"
}

stop_middleware() {
  info "停止 MySQL 和 Redis..."
  docker_compose -f "$COMPOSE_FILE" down
}

run_detached() {
  local log_file=$1
  shift
  /bin/bash -c "($*) >>\"$log_file\" 2>&1 < /dev/null &"
}

start_backend() {
  local pid_file="$PID_DIR/backend.pid"
  local existing
  existing=$(read_pid "$pid_file")
  if is_pid_running "$existing"; then
    warn "后端已在运行 (PID: $existing)"
    return
  fi
  if is_port_open localhost "$BACKEND_PORT"; then
    warn "端口 $BACKEND_PORT 已被占用，跳过后端启动"
    return
  fi

  if [[ ! -x "$BACKEND_DIR/mvnw" ]]; then
    warn "mvnw 缺少执行权限，正在修复..."
    chmod +x "$BACKEND_DIR/mvnw"
  fi

  info "启动后端 (Spring Boot)..."
  run_detached "$LOG_DIR/backend.log" "cd '$BACKEND_DIR' && exec ./mvnw spring-boot:run"

  wait_for_port localhost "$BACKEND_PORT" "后端 API" 120
  local service_pid
  service_pid=$(lsof -ti:"$BACKEND_PORT" 2>/dev/null | head -1 || true)
  [[ -n "$service_pid" ]] && echo "$service_pid" >"$pid_file"
}

start_frontend() {
  local pid_file="$PID_DIR/frontend.pid"
  local existing
  existing=$(read_pid "$pid_file")
  if is_pid_running "$existing"; then
    warn "前端已在运行 (PID: $existing)"
    return
  fi
  if is_port_open localhost "$FRONTEND_PORT"; then
    warn "端口 $FRONTEND_PORT 已被占用，跳过前端启动"
    return
  fi

  info "安装前端依赖（如需）..."
  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    (cd "$FRONTEND_DIR" && pnpm install) >>"$LOG_DIR/frontend.log" 2>&1
  fi

  info "启动前端 (Next.js)..."
  run_detached "$LOG_DIR/frontend.log" "cd '$FRONTEND_DIR' && exec pnpm dev"

  wait_for_port localhost "$FRONTEND_PORT" "前端" 60
  local service_pid
  service_pid=$(lsof -ti:"$FRONTEND_PORT" 2>/dev/null | head -1 || true)
  [[ -n "$service_pid" ]] && echo "$service_pid" >"$pid_file"
}

stop_backend() {
  local pid_file="$PID_DIR/backend.pid"
  local pid
  pid=$(read_pid "$pid_file")
  info "停止后端..."
  kill_pid_tree "$pid"
  kill_port "$BACKEND_PORT"
  rm -f "$pid_file"
}

stop_frontend() {
  local pid_file="$PID_DIR/frontend.pid"
  local pid
  pid=$(read_pid "$pid_file")
  info "停止前端..."
  kill_pid_tree "$pid"
  kill_port "$FRONTEND_PORT"
  rm -f "$pid_file"
}

print_banner() {
  echo
  ok "开发环境已启动"
  echo "  前端:     http://localhost:$FRONTEND_PORT"
  echo "  后端 API: http://localhost:$BACKEND_PORT"
  echo "  Swagger:  http://localhost:$BACKEND_PORT/swagger-ui.html"
  echo
  echo "  日志目录: $LOG_DIR"
  echo "  查看日志: ./scripts/dev.sh logs"
  echo "  停止服务: ./scripts/dev.sh stop"
  echo
}

cmd_start() {
  setup_java
  check_node
  ensure_pnpm

  start_middleware
  start_backend
  start_frontend
  print_banner
}

cmd_stop() {
  local stop_all=false
  if [[ "${1:-}" == "--all" ]]; then
    stop_all=true
  fi

  stop_frontend
  stop_backend

  if $stop_all; then
    stop_middleware
    ok "已停止全部服务（含中间件）"
  else
    ok "已停止前后端（MySQL/Redis 仍在运行）"
    echo "  如需停止中间件: ./scripts/dev.sh stop --all"
  fi
}

service_status() {
  local name=$1 port=$2 pid_file=$3
  local pid status color
  pid=$(read_pid "$pid_file")
  if is_pid_running "$pid" || is_port_open localhost "$port"; then
    status="运行中"
    color="$GREEN"
  else
    status="未运行"
    color="$RED"
  fi
  printf "  %-10s %b%-8s%b  port=%-5s  pid=%s\n" "$name" "$color" "$status" "$NC" "$port" "${pid:--}"
}

cmd_status() {
  echo
  info "服务状态"
  service_status "MySQL" "$MYSQL_PORT" /dev/null
  service_status "Redis" "$REDIS_PORT" /dev/null
  service_status "后端" "$BACKEND_PORT" "$PID_DIR/backend.pid"
  service_status "前端" "$FRONTEND_PORT" "$PID_DIR/frontend.pid"
  echo
}

cmd_logs() {
  local target=${1:-all}
  case "$target" in
    backend)
      tail -f "$LOG_DIR/backend.log"
      ;;
    frontend)
      tail -f "$LOG_DIR/frontend.log"
      ;;
    all|*)
      tail -f "$LOG_DIR/backend.log" "$LOG_DIR/frontend.log"
      ;;
  esac
}

usage() {
  cat <<EOF
用法: ./scripts/dev.sh [command] [options]

命令:
  start          启动中间件、后端、前端（默认）
  stop           停止前后端，保留 MySQL/Redis
  stop --all     停止全部服务（含中间件）
  restart        重启前后端
  status         查看服务状态
  logs [target]  跟踪日志，target: backend | frontend | all（默认）

示例:
  ./scripts/dev.sh
  ./scripts/dev.sh logs frontend
  ./scripts/dev.sh stop --all
EOF
}

main() {
  local cmd=${1:-start}
  shift || true

  case "$cmd" in
    start|"")
      cmd_start
      ;;
    stop)
      cmd_stop "${1:-}"
      ;;
    restart)
      cmd_stop
      sleep 1
      cmd_start
      ;;
    status)
      cmd_status
      ;;
    logs)
      cmd_logs "${1:-all}"
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      error "未知命令: $cmd"
      usage
      exit 1
      ;;
  esac
}

main "$@"
