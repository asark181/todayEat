# nginx 改动说明（leafy.fxxkopenai.com，与暖月 / 记账同机）

目标文件：`/etc/nginx/sites-available/nuanyue`（Cloudflare 回源 :80 的那个 server 块；**不要碰 443**，x-ui/xray 在用）。

日常不需要手改：`deploy/deploy.sh` 会把 `deploy/patch_nginx.py` 装到 `/opt/todayeat/patch_nginx.py` 并执行，它会

1. 找到 `server_name` 含 `leafy.fxxkopenai.com`（或 root 为 `/opt/nuanyue/web`）的 server 块；
2. 逐个检查下面的 location，**已存在就跳过**（幂等，可反复运行）；
3. 缺的块统一包在 `# BEGIN todayeat（deploy/patch_nginx.py 维护，勿手改）` / `# END todayeat` 之间，插到该 server 块**第一个 `location` 之前**；
4. 改前备份到 `~/backup/nginx-<时间戳>.conf`，`nginx -t` 失败则自动还原并以非 0 退出。

手动执行：`sudo python3 /opt/todayeat/patch_nginx.py --backup-dir ~/backup`（`--dry-run` 只打印结果，`--no-reload` 不 reload）。

## 要插入的 location 块

```nginx
    location ^~ /api/te/ {
        proxy_pass http://127.0.0.1:8096;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 60s;
        client_max_body_size 6m;
    }

    location = / {
        root /opt/appstore/web;
        try_files /index.html =404;
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options nosniff always;
    }

    location ^~ /store/ {
        alias /opt/appstore/web/store/;
        add_header Cache-Control "public, max-age=3600";
        add_header X-Content-Type-Options nosniff always;
    }

    location = /nuanyue.html {
        alias /opt/nuanyue/web/index.html;
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options nosniff always;
    }

    location = /todayeat.html {
        alias /opt/todayeat/web/todayeat.html;
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options nosniff always;
    }

    location ^~ /todayeat-assets/ {
        alias /opt/todayeat/web/assets/;
        add_header Cache-Control "public, max-age=3600";
        add_header X-Content-Type-Options nosniff always;
    }

    location = /todayeat-version.json {
        alias /opt/todayeat/web/todayeat-version.json;
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options nosniff always;
    }

    location = /download/todayeat.apk {
        alias /opt/todayeat/web/download/todayeat.apk;
        types { application/vnd.android.package-archive apk; }
        add_header Content-Disposition 'attachment; filename="todayeat.apk"';
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options nosniff always;
    }
```

## 插入位置与优先级

nginx 选 location 的规则：先看精确匹配 `=`，命中即用；再在前缀匹配里取**最长**的那条，若它带 `^~` 就不再看正则；否则按书写顺序试正则。所以：

| 新块 | 与现有块的关系 | 为什么不会被抢 |
|---|---|---|
| `^~ /api/te/` | 现有通用 `location /api/`（→ 暖月 8090）、记账的 `/api/auth/`、`= /api/sync` | `/api/te/` 比 `/api/` 长，最长前缀胜；`^~` 再保证不被任何正则 location 截走。和记账的路径不重叠。 |
| `= /` | 现有 `location /`（暖月静态根） | 精确匹配优先级最高，只接管根路径 `/`，其他静态文件仍走暖月 `location /`。 |
| `^~ /store/` | `location /` | 更长前缀 + `^~`。 |
| `= /nuanyue.html` | `location /` | 精确匹配；alias 到暖月原首页，**不改暖月文件**，暖月 deploy 照常覆盖 `/opt/nuanyue/web`。 |
| `= /todayeat.html`、`= /todayeat-version.json` | `location /` | 精确匹配，文件来自 `/opt/todayeat/web`。 |
| `= /download/todayeat.apk` | 现有 `location /download/`（固定加 `filename="nuanyue.apk"`） | 精确匹配优先于前缀 `/download/`，否则下载文件名会被写成 nuanyue.apk。 |
| `^~ /todayeat-assets/` | `location /` | 吃了吗下载页的图片 / CSS 等资源（`web/todayeat/assets/` → `/opt/todayeat/web/assets/`）。 |

因为全部用 `=` 或 `^~`（最长前缀），**书写顺序本身不影响结果**；脚本仍插在第一个 location 之前（即通用 `/api/`、`/download/` 之前），读配置时一目了然。

其他要点：

- `client_max_body_size 6m` 只放在 `/api/te/` 里：照片密文 ≤ 4MB、同步快照 ≤ 2MB；服务端另有 5MB 硬上限兜底。
- location 内只要写了 `add_header`，就**不再继承** server 级的 `add_header`，所以每块都补了 `X-Content-Type-Options nosniff`。
- `= /` 用 `root + try_files /index.html` 而不是 `index`：`index` 会内部跳转到 `/index.html`，又落回暖月的 `location /`。
- 网页约定：`todayeat.html` 引用资源请用绝对路径 `/todayeat-assets/...`；商店首页引用资源用 `/store/...`。

## 验证

```bash
curl -s -H 'Host: leafy.fxxkopenai.com' http://127.0.0.1/api/te/health          # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: leafy.fxxkopenai.com' http://127.0.0.1/api/te/sync   # 401（JSON），不是 404
curl -sI https://leafy.fxxkopenai.com/download/todayeat.apk | grep -i content-disposition   # filename="todayeat.apk"
curl -sI https://leafy.fxxkopenai.com/download/nuanyue.apk  | grep -i content-disposition   # 暖月不受影响
curl -s  https://leafy.fxxkopenai.com/api/health                                            # 暖月接口不受影响
```

## 风险：暖月部署会覆盖整个站点配置

`Period/deploy/deploy.sh` 会执行 `cp nginx-nuanyue.conf /etc/nginx/sites-available/nuanyue`，**整文件覆盖**——记账与吃了吗加的 location 都会丢。暖月每次部署后需要重新运行：

```bash
ssh oracle-main 'sudo python3 /opt/todayeat/patch_nginx.py --backup-dir ~/backup'
```

（记账的转发块不归本脚本管，也需按记账文档补回。）长期应把这些块合并进暖月仓库的 `nginx-nuanyue.conf`，或改为 `include` 独立片段。

