#!/usr/bin/env python3
"""
幂等地把「吃了吗」与应用商店需要的 location 块插入现有 nginx 站点配置（默认 /etc/nginx/sites-available/nuanyue）。

- 只改与 leafy.fxxkopenai.com / 暖月静态根目录对应的 server 块；每个 location 已存在则跳过。
- 新块插在该 server 块第一个 location 之前（并以前缀 ^~ 或精确 = 匹配，顺序本身不影响优先级）。
- 修改前备份到 <backup-dir>/nginx-<时间戳>.conf；nginx -t 失败则还原并以非 0 退出；成功则 reload。

用法（服务器上）：sudo python3 patch_nginx.py [--conf PATH] [--backup-dir DIR] [--no-reload] [--dry-run]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

MARK_BEGIN = "# BEGIN todayeat（deploy/patch_nginx.py 维护，勿手改）"
MARK_END = "# END todayeat"

NOSNIFF = "add_header X-Content-Type-Options nosniff always;"

# (允许视为「已存在」的修饰符集合, 路径, 块内容)。
# 注意：location 内一旦写了 add_header，就不再继承 server 级的 add_header，所以每块都补上 nosniff。
BLOCKS = [
    ({"^~", ""}, "/api/te/", """location ^~ /api/te/ {
    proxy_pass http://127.0.0.1:8096;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 60s;
    client_max_body_size 6m;
}"""),
    ({"="}, "/", f"""location = / {{
    root /opt/appstore/web;
    try_files /index.html =404;
    add_header Cache-Control "no-cache";
    {NOSNIFF}
}}"""),
    ({"^~", ""}, "/store/", f"""location ^~ /store/ {{
    alias /opt/appstore/web/store/;
    add_header Cache-Control "public, max-age=3600";
    {NOSNIFF}
}}"""),
    ({"="}, "/nuanyue.html", f"""location = /nuanyue.html {{
    alias /opt/nuanyue/web/index.html;
    add_header Cache-Control "no-cache";
    {NOSNIFF}
}}"""),
    ({"="}, "/todayeat.html", f"""location = /todayeat.html {{
    alias /opt/todayeat/web/todayeat.html;
    add_header Cache-Control "no-cache";
    {NOSNIFF}
}}"""),
    ({"^~", ""}, "/todayeat-assets/", f"""location ^~ /todayeat-assets/ {{
    alias /opt/todayeat/web/assets/;
    add_header Cache-Control "public, max-age=3600";
    {NOSNIFF}
}}"""),
    ({"="}, "/todayeat-version.json", f"""location = /todayeat-version.json {{
    alias /opt/todayeat/web/todayeat-version.json;
    add_header Cache-Control "no-cache";
    {NOSNIFF}
}}"""),
    ({"="}, "/download/todayeat.apk", f"""location = /download/todayeat.apk {{
    alias /opt/todayeat/web/download/todayeat.apk;
    types {{ application/vnd.android.package-archive apk; }}
    add_header Content-Disposition 'attachment; filename="todayeat.apk"';
    add_header Cache-Control "no-cache";
    {NOSNIFF}
}}"""),
]

SERVER_HINTS = ("leafy.fxxkopenai.com", "/opt/nuanyue/web")


def scan_blocks(text: str):
    """
    简单词法扫描（忽略注释与引号内的花括号），返回顶层 server 块列表：[(start, open_brace, close_brace)]。
    """
    result = []
    depth = 0
    i, n = 0, len(text)
    quote = None
    stack = []
    while i < n:
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c == "#":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        elif c in "'\"":
            quote = c
        elif c == "{":
            stack.append(i)
            depth += 1
        elif c == "}":
            if not stack:
                raise ValueError("花括号不匹配")
            open_at = stack.pop()
            depth -= 1
            if depth == 0:
                head_start = text.rfind(";", 0, open_at)
                head_start = max(head_start, text.rfind("}", 0, open_at)) + 1
                head = text[head_start:open_at]
                head_clean = re.sub(r"#[^\n]*", "", head).strip()
                if head_clean == "server":
                    result.append((head_start, open_at, i))
        i += 1
    if stack:
        raise ValueError("花括号不匹配")
    return result


def location_exists(body: str, modifiers: set, path: str) -> bool:
    body = re.sub(r"#[^\n]*", "", body)
    for m in re.finditer(r"\blocation\s+(=|\^~|~\*|~)?\s*(\S+)\s*\{", body):
        if (m.group(1) or "") in modifiers and m.group(2) == path:
            return True
    return False


def indent_block(block: str, pad: str) -> str:
    return "\n".join((pad + line) if line else line for line in block.splitlines())


def patch_text(text: str):
    """返回 (新文本, 新增的 location 列表, 跳过的 location 列表)"""
    servers = [s for s in scan_blocks(text) if any(h in text[s[1]:s[2]] for h in SERVER_HINTS)]
    if not servers:
        raise SystemExit("找不到 leafy.fxxkopenai.com / 暖月对应的 server 块，未做修改")
    added, skipped = [], []
    # 从后往前改，前面块的偏移不受影响
    for _, open_at, close_at in reversed(servers):
        body = text[open_at + 1:close_at]
        missing = []
        for mods, path, block in BLOCKS:
            label = f"location {'= ' if mods == {'='} else '^~ '}{path}"
            if location_exists(body, mods, path):
                skipped.append(label)
            else:
                missing.append(block)
                added.append(label)
        if not missing:
            continue
        loc = re.search(r"^([ \t]*)location\b", body, re.M)
        pad = loc.group(1) if loc else "    "
        insert_at = open_at + 1 + (loc.start() if loc else len(body.rstrip()))
        chunk = (
            f"{pad}{MARK_BEGIN}\n"
            + "\n\n".join(indent_block(b, pad) for b in missing)
            + f"\n{pad}{MARK_END}\n\n"
        )
        if not loc:
            chunk = "\n" + chunk
        text = text[:insert_at] + chunk + text[insert_at:]
    return text, added, skipped


def main() -> int:
    ap = argparse.ArgumentParser(description="幂等插入吃了吗的 nginx location 块")
    ap.add_argument("--conf", default="/etc/nginx/sites-available/nuanyue")
    ap.add_argument("--backup-dir", default=os.path.expanduser("~/backup"))
    ap.add_argument("--nginx-bin", default="nginx")
    ap.add_argument("--no-reload", action="store_true", help="改完只做 nginx -t，不 reload")
    ap.add_argument("--dry-run", action="store_true", help="只打印将要插入的结果，不写文件")
    args = ap.parse_args()

    conf = os.path.realpath(args.conf)
    with open(conf, "r", encoding="utf-8") as f:
        original = f.read()

    patched, added, skipped = patch_text(original)
    for label in skipped:
        print(f"[跳过] 已存在 {label}")
    if not added:
        print("nginx 配置已包含全部 location，无需修改")
        return 0
    for label in added:
        print(f"[新增] {label}")

    if args.dry_run:
        print("----- dry-run，修改后的配置如下 -----")
        print(patched)
        return 0

    os.makedirs(args.backup_dir, exist_ok=True)
    backup = os.path.join(args.backup_dir, f"nginx-{time.strftime('%Y%m%d-%H%M%S')}.conf")
    shutil.copy2(conf, backup)
    print(f"已备份原配置到 {backup}")

    tmp = conf + ".todayeat.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(patched)
    shutil.copymode(conf, tmp)
    os.replace(tmp, conf)

    test = subprocess.run([args.nginx_bin, "-t"], capture_output=True, text=True)
    if test.returncode != 0:
        shutil.copy2(backup, conf)
        sys.stderr.write(test.stdout + test.stderr)
        sys.stderr.write(f"nginx -t 失败，已从 {backup} 还原\n")
        return 1
    print("nginx -t 通过")

    if not args.no_reload:
        r = subprocess.run(["systemctl", "reload", "nginx"])
        if r.returncode != 0:
            print("systemctl reload nginx 失败，请手动检查", file=sys.stderr)
            return r.returncode
        print("nginx 已 reload")
    return 0


if __name__ == "__main__":
    sys.exit(main())
