#!/usr/bin/env bash
# 用法：[SSH_BIND_INTERFACE=en0] [DEPLOY_KEY=~/.ssh/xxx] [HOST=oracle-main] [NOTES="更新说明"] deploy/deploy.sh [apk]
# 把「吃了吗」服务端、应用商店首页、吃了吗下载页以及（可选）新 APK 部署到 oracle-main。
# - HOST 默认用 ~/.ssh/config 里的别名 oracle-main（已配好用户与密钥）；DEPLOY_KEY 可选，用于临时指定私钥。
# - web/ 下某个目录不存在时跳过对应步骤并提示。
# - 远端：建用户与目录 → 备份旧文件到 ~/backup/todayeat-<时间戳>/ → 覆盖 → venv 装依赖 → 装 service/cron
#         → patch_nginx.py 幂等插入 location → 重启 → 健康检查。
set -euo pipefail
APK=${1:-}
HOST=${HOST:-oracle-main}
NOTES=${NOTES:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SSH_ARGS=(-o StrictHostKeyChecking=accept-new)
if [[ -n "${DEPLOY_KEY:-}" ]]; then
  SSH_ARGS+=(-i "$DEPLOY_KEY")
fi
if [[ -n "${SSH_BIND_INTERFACE:-}" ]]; then
  SSH_ARGS+=(-o "BindInterface=$SSH_BIND_INTERFACE")
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE" "$STAGE.tgz"' EXIT
mkdir -p "$STAGE/app" "$STAGE/deploy"
cp "$ROOT/server/app.py" "$ROOT/server/requirements.txt" "$STAGE/app/"
cp "$ROOT/deploy/todayeat.service" "$ROOT/deploy/todayeat-backup.cron" "$ROOT/deploy/patch_nginx.py" "$STAGE/deploy/"

# ── 应用商店首页与静态资源 ──
if [[ -f "$ROOT/web/store/index.html" ]]; then
  mkdir -p "$STAGE/appstore"
  cp "$ROOT/web/store/index.html" "$STAGE/appstore/index.html"
  if [[ -d "$ROOT/web/store/assets" ]]; then
    mkdir -p "$STAGE/appstore/store"
    cp -R "$ROOT/web/store/assets/." "$STAGE/appstore/store/"
  else
    echo "提示：web/store/assets/ 不存在，跳过商店静态资源"
  fi
else
  echo "提示：web/store/index.html 不存在，跳过应用商店首页"
fi

# ── 吃了吗下载页（todayeat.html 及 assets/ 等资源，整目录放到 /opt/todayeat/web/）──
if [[ -d "$ROOT/web/todayeat" ]]; then
  mkdir -p "$STAGE/todayeat-web"
  cp -R "$ROOT/web/todayeat/." "$STAGE/todayeat-web/"
  [[ -f "$STAGE/todayeat-web/todayeat.html" ]] || echo "提示：web/todayeat/ 下没有 todayeat.html"
else
  echo "提示：web/todayeat/ 不存在，跳过吃了吗下载页"
fi

# ── 可选：APK 与版本文件 ──
if [[ -n "$APK" ]]; then
  [[ -f "$APK" ]] || { echo "找不到 APK：$APK" >&2; exit 1; }
  GRADLE="$ROOT/android/app/build.gradle.kts"
  [[ -f "$GRADLE" ]] || { echo "找不到 $GRADLE，无法读取版本号" >&2; exit 1; }
  VERSION_CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE" | head -1 | grep -oE '[0-9]+')
  VERSION_NAME=$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE" | head -1 | grep -oE '"[^"]+"' | tr -d '"')
  [[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || { echo "无法从 $GRADLE 解析 versionCode/versionName" >&2; exit 1; }
  mkdir -p "$STAGE/todayeat-web/download"
  cp "$APK" "$STAGE/todayeat-web/download/todayeat.apk"
  # 用 python 生成 JSON，NOTES 里的引号、换行都能正确转义
  python3 - "$APK" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" > "$STAGE/todayeat-web/todayeat-version.json" <<'PY'
import hashlib, json, os, sys
apk, code, name, notes = sys.argv[1:5]
h = hashlib.sha256()
with open(apk, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
size = os.path.getsize(apk)
print(json.dumps({
    "versionCode": int(code),
    "versionName": name,
    "url": "https://leafy.fxxkopenai.com/todayeat.html",
    "apkUrl": "https://leafy.fxxkopenai.com/download/todayeat.apk",
    "sha256": h.hexdigest(),
    "size": f"{size / 1048576:.1f}MB",
    "notes": notes,
}, ensure_ascii=False))
PY
  echo "版本文件：$(cat "$STAGE/todayeat-web/todayeat-version.json")"
fi

COPYFILE_DISABLE=1 tar --no-xattrs -C "$STAGE" -czf "$STAGE.tgz" .
scp "${SSH_ARGS[@]}" "$STAGE.tgz" "$HOST:/tmp/todayeat-deploy.tgz"

TS=$(date +%Y%m%d-%H%M%S)
ssh "${SSH_ARGS[@]}" "$HOST" "TS=$TS bash -s" <<'REMOTE'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v nginx >/dev/null || ! dpkg -s python3-venv >/dev/null 2>&1 || ! command -v sqlite3 >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq nginx python3-venv sqlite3 >/dev/null
fi

T=$(mktemp -d); tar -C "$T" -xzf /tmp/todayeat-deploy.tgz; rm -f /tmp/todayeat-deploy.tgz
trap 'rm -rf "$T"' EXIT

# ── 用户与目录 ──
id todayeat >/dev/null 2>&1 || sudo useradd --system --home /var/lib/todayeat --shell /usr/sbin/nologin todayeat
sudo mkdir -p /opt/todayeat/app /opt/todayeat/web/download /var/lib/todayeat/photos /var/lib/todayeat/backups /opt/appstore/web/store
sudo chown -R todayeat:todayeat /var/lib/todayeat
sudo chmod 750 /var/lib/todayeat

# ── 备份旧文件 ──
BK="$HOME/backup/todayeat-$TS"
mkdir -p "$BK"
for p in /opt/todayeat/app /opt/todayeat/web /opt/appstore/web /etc/systemd/system/todayeat.service /etc/cron.d/todayeat-backup; do
  if [[ -e "$p" ]]; then sudo cp -a "$p" "$BK/$(echo "${p#/}" | tr / _)"; fi
done
if [[ -f /var/lib/todayeat/todayeat.db ]]; then
  sudo sqlite3 /var/lib/todayeat/todayeat.db ".backup '$BK/todayeat.db'"
fi
sudo chown -R "$(id -un)":"$(id -gn)" "$BK"
echo "旧文件已备份到 $BK"

# ── 服务端代码 ──
sudo install -m 644 "$T"/app/app.py "$T"/app/requirements.txt /opt/todayeat/app/
sudo install -m 755 "$T"/deploy/patch_nginx.py /opt/todayeat/patch_nginx.py

# ── 静态文件 ──
if [[ -f "$T/appstore/index.html" ]]; then
  sudo install -m 644 "$T/appstore/index.html" /opt/appstore/web/index.html
  if [[ -d "$T/appstore/store" ]]; then sudo cp -R "$T/appstore/store/." /opt/appstore/web/store/; fi
  echo "应用商店首页已更新"
else
  echo "本次未包含应用商店首页，跳过"
fi
if [[ -d "$T/todayeat-web" ]]; then
  sudo cp -R "$T/todayeat-web/." /opt/todayeat/web/
  echo "吃了吗下载页 / APK 已更新"
else
  echo "本次未包含吃了吗网页与 APK，跳过"
fi
sudo chmod -R a+rX /opt/todayeat/web /opt/appstore/web

# ── venv 与依赖 ──
[[ -x /opt/todayeat/venv/bin/python ]] || sudo python3 -m venv /opt/todayeat/venv
sudo /opt/todayeat/venv/bin/pip install -q --upgrade pip
sudo /opt/todayeat/venv/bin/pip install -q -r /opt/todayeat/app/requirements.txt

# ── service 与 cron ──
sudo install -m 644 "$T"/deploy/todayeat.service /etc/systemd/system/todayeat.service
sudo install -m 644 "$T"/deploy/todayeat-backup.cron /etc/cron.d/todayeat-backup
sudo systemctl daemon-reload
sudo systemctl enable -q todayeat
sudo systemctl restart todayeat

# ── nginx：幂等插入 location，nginx -t 失败会自动还原 ──
sudo python3 /opt/todayeat/patch_nginx.py --backup-dir "$HOME/backup" --no-reload
sudo nginx -t -q
sudo systemctl reload nginx

# ── 健康检查 ──
sleep 2
if ! curl -fsS http://127.0.0.1:8096/api/te/health; then
  echo; echo "todayeat 健康检查失败，最近日志：" >&2
  sudo journalctl -u todayeat -n 50 --no-pager >&2
  exit 1
fi
echo
echo -n "经 nginx：";       curl -fsS -H 'Host: leafy.fxxkopenai.com' http://127.0.0.1/api/te/health; echo
echo -n "暖月 /api/health："; curl -fsS -o /dev/null -w '%{http_code}\n' -H 'Host: leafy.fxxkopenai.com' http://127.0.0.1/api/health || true
echo -n "首页 /：";           curl -fsS -o /dev/null -w '%{http_code}\n' -H 'Host: leafy.fxxkopenai.com' http://127.0.0.1/ || true
systemctl is-active todayeat nginx nuanyue bookkeeping x-ui || true
REMOTE
echo "已部署 → https://leafy.fxxkopenai.com/todayeat.html （API：/api/te/）"
