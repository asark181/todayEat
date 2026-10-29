# 吃了吗 · 服药打卡 —— 设计与接口契约

> 这份文档是 Android、服务端、网页三条线共同遵守的契约。改接口先改这里。

## 1. 产品

使用者：术后需要每天早上 6:30 左右吃药的人。吃完药常常回去睡回笼觉，醒来记不清吃没吃。

核心问题只有一个：**醒来时一眼就能确认今天吃没吃**。其余功能都围着它转。

- **提醒**：每个药单独设提醒时间与频率（每天 / 工作日（周一至周五）/ 周末 / 指定星期 / 每隔 N 天）。通知上直接有「已吃」按钮，半睡半醒按一下就记上；没按的话按设定间隔（默认 10 分钟，最多 3 次）再提醒。
- **导航**：底部两个标签「我的」「设置」。「我的」里用分段切换「打卡」和「日历」两种看法，看的是同一份记录。
- **打卡**：本周七天一排，中间一个大圆按钮。按一下把这天该吃还没记的药全部记上；已打卡显示对勾和时间。点上面某天可以看那天、补打卡。
- **日历**：月历，每天一个格子：已吃 / 部分 / 漏吃。点某天看当天的记录。
- **记录**：一天可以有多条，每条有药品、日期、时刻、备注、照片（最多 4 张），都能添加、修改、删除。打卡按钮和通知上的「已吃」只在这种药这天还没有记录时新增，不会重复记。
- **账号与云同步**：用户名 + 密码注册登录；多设备同步打卡记录、药品计划和照片。端到端加密，服务器只存密文。
- **应用内更新**：同记账 / 暖月的协议。

不做：药品库、用量统计图表、社交、AI 建议、健康打分。

### 文案与视觉

- 语气：短、直接，只写操作和状态，不写安慰话、问候语和解释性的长句；不用感叹号，不用 emoji。
  - 打卡按钮：「打卡」/「已打卡」/「未到」/「无需服药」
  - 按钮下方：「07:30 早上的药」/「06:32 打卡」
  - 通知：「该吃药了」/「06:30 · 早上的药 · 1 片」，按钮「已吃」「10 分钟后提醒」
- 色板（App 与网页共用）：

| 令牌 | 值 | 用途 |
|---|---|---|
| paper | `#F6F3EE` | 背景，暖纸色 |
| card | `#FFFFFF` | 卡片 |
| ink | `#2B2722` | 主文字 |
| ink2 | `#6E665C` | 次文字 |
| ink3 | `#8E867B` | 弱文字 |
| line | `#E9E3DA` | 分割线 |
| sage | `#4F8A76` | 主色，「已吃」 |
| sageSoft | `#E3EFE9` | 已吃格子底 |
| amber | `#D9A24A` | 部分 / 待吃 |
| amberSoft | `#F8EDD6` | |
| clay | `#C9785D` | 漏吃 |
| claySoft | `#F6E3DB` | |
| 深色 paper | `#171512`，card `#211F1B`，ink `#EDE7DD`，ink2 `#B3AA9D`，sage `#7DB8A2` | |

- 字体：App 用系统字体；数字用等宽数字。网页标题可用 Noto Serif SC 增加人情味，正文 Noto Sans SC。
- 图标：鼠尾草绿底，一颗白色胶囊斜放，后面半轮浅杏色的晨光。

## 2. 部署拓扑（甲骨文 `oracle-main`）

- 域名 `https://leafy.fxxkopenai.com`，Cloudflare 终结 HTTPS，回源 nginx :80（**不要碰 443，x-ui/xray 在用**）。
- 已有：暖月 `nuanyue.service` 127.0.0.1:8090（`/api/` 兜底转发给它），记账 `bookkeeping.service` 127.0.0.1:8095（`/api/auth/`、`/api/user/`、`= /api/sync`）。
- 新增：`todayeat.service` 127.0.0.1:**8096**，用户 `todayeat`，代码 `/opt/todayeat/app`，venv `/opt/todayeat/venv`，数据 `/var/lib/todayeat`（db + photos/），每日备份。
- nginx 新增 `location /api/te/ → 8096`，`client_max_body_size 6m`。
- 静态：
  - 应用商店首页 `/` → `/opt/appstore/web/index.html`（nginx `location = /`），商店静态资源 `/store/...` → `/opt/appstore/web/store/`。
  - 暖月原首页改由 `/nuanyue.html` 访问（nginx `location = /nuanyue.html { alias /opt/nuanyue/web/index.html; }`），**不改暖月的文件**，暖月 deploy 脚本照常覆盖 `/opt/nuanyue/web`。
  - 记账下载页仍是 `/ledger.html`（在 `/opt/nuanyue/web`）。
  - 吃了吗：`/todayeat.html`、`/todayeat-version.json`、`/download/todayeat.apk` 均来自 `/opt/todayeat/web`（nginx 精确 location）。

## 3. 服务端 API（前缀 `/api/te`）

统一错误：`{"status":"error","detail":"<中文>"}`；成功都带 `"status":"ok"`。鉴权头 `Authorization: Bearer <token>`。

### 加密与账号

客户端：`PBKDF2-HMAC-SHA256(password, salt="todayeat-v1:" + lower(trim(username)), 150000 轮, 64 字节)`

- 前 32 字节 = AES-256-GCM 数据密钥（只在本机）
- 后 32 字节的小写 hex = `auth`（发给服务器当密码用）

服务器只见到 `auth`，再做一次 PBKDF2(随机盐, 100000 轮) 存储。所以服务器拿不到数据密钥。忘记密码 = 云端数据无法解密（文案里要说明）。

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| GET | `/api/te/health` | | `{"status":"ok"}` |
| POST | `/api/te/auth/register` | `{"username","auth","device"}` | `{"status":"ok","token","user":{"id","username"}}`；重名 409 |
| POST | `/api/te/auth/login` | `{"username","auth","device"}` | 同上；错误 401「用户名或密码不对」 |
| POST | `/api/te/auth/logout` | 鉴权 | `{"status":"ok"}`，删除当前会话 |
| GET | `/api/te/auth/me` | 鉴权 | `{"status":"ok","user":{...}}` |
| GET | `/api/te/sync` | 鉴权 | `{"status":"ok","version":int,"blob":str\|null,"updated_at":ms}` |
| PUT | `/api/te/sync` | `{"base_version":int,"blob":str}`（blob ≤ 2MB） | `{"status":"ok","version","updated_at"}`；版本不一致 409 |
| GET | `/api/te/photos` | 鉴权 | `{"status":"ok","ids":[...]}` |
| PUT | `/api/te/photos/{id}` | `application/octet-stream` 密文，≤ 4MB；id 为 32 位小写 hex | `{"status":"ok"}`，幂等 |
| GET | `/api/te/photos/{id}` | 鉴权 | 密文字节，404 不存在 |
| DELETE | `/api/te/photos/{id}` | 鉴权 | `{"status":"ok"}`，幂等 |

- 用户名：2–32 位，字母数字下划线中文均可，大小写不敏感唯一。`auth` 必须是 64 位 hex。
- 每账号最多 10 个会话（多设备），超出淘汰最久未用的。令牌 32 字节随机，库里只存 sha256。
- 限流：每 IP 120 次/分钟；登录/注册失败每 IP 10 分钟 20 次。
- 每用户照片上限 3000 张。照片存 `/var/lib/todayeat/photos/<user_id>/<id>.bin`。

### 同步快照格式（明文 JSON，加密后放进 blob）

`blob = base64(12 字节 IV + AES-GCM(gzip(json)))`

```json
{
  "schema": 1,
  "plans": [{"uid":"…","name":"…","dose":"1 片","hour":6,"minute":30,
             "repeat":"DAILY|WEEKDAYS|WEEKENDS|CUSTOM|INTERVAL","weekdays":[1,2,3,4,5],
             "intervalDays":2,"startDate":"2026-05-01","renag":true,"renagMinutes":10,
             "active":true,"updatedAt":ms,"deleted":false}],
  "doses":  [{"uid":"…","planUid":"…","date":"2026-10-29","takenAt":ms,"note":"",
              "photos":["<32hex>"],"updatedAt":ms,"deleted":false}]
}
```

合并：按 uid 取 `updatedAt` 大者，墓碑参与合并。同一 (planUid, date) 允许多条记录，不去重。照片：本地有云端没有就上传，云端有本地没有就按需下载（显示时再拉）。照片密文 = `IV + AES-GCM(jpeg)`（不 gzip）。

### 版本文件

`/todayeat-version.json`：`{"versionCode","versionName","url":"https://leafy.fxxkopenai.com/todayeat.html","apkUrl":"https://leafy.fxxkopenai.com/download/todayeat.apk","sha256","size","notes"}`

## 4. Android

- 包名 `com.todayeat.app`，应用名「吃了吗」，minSdk 26，target/compile 35，Kotlin 2.0.21 + Compose + Room + WorkManager（与记账同版本线）。
- release 签名用独立 keystore（`android/keystore/`，gitignore；密码在 `deploy/private/`）。
- 小米 15 Pro（HyperOS 2 / Android 15，1440×3200）适配：edge-to-edge，精确闹钟 `USE_EXACT_ALARM`，开机/改时间重排，引导开启自启动与省电无限制，通知渠道重要性 HIGH。
