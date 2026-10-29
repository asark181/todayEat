# 吃了吗 · 服药打卡

术后每天早上 6:30 吃药，吃完常常又睡过去，醒来记不清吃没吃。这个安卓 App 只解决这一件事：**醒来一眼就能确认今天吃过没有**。

- 下载页：https://leafy.fxxkopenai.com/todayeat.html
- 应用商店（吃了吗 / 暖月 / 智能记账）：https://leafy.fxxkopenai.com/
- 设计与接口契约：[docs/SPEC.md](docs/SPEC.md)

## 功能

- 底部两个标签：「我的」（打卡 / 日历两种看法）和「设置」
- 每种药单独设提醒时间与频率：每天 / 工作日（周一至周五，不跳法定节假日）/ 周末 / 指定星期 / 每隔 N 天
- 到点通知，通知上直接点「已吃」；没点会按 5/10/15/30 分钟再提醒，最多 3 次；「10 分钟后再提醒」
- 打卡：本周七天 + 一个大圆按钮，按一下记上这天该吃的药
- 月历：已吃 / 部分 / 漏吃；点某天看当天记录
- 记录可增删改：一天多条，每条有药品、日期、时刻、备注、照片（相机或相册，最多 4 张）
- 账号 + 端到端加密云同步（记录、计划、照片），多设备合并；忘记密码云端数据无法解密
- 应用内更新（读 `/todayeat-version.json`，校验 sha256）
- 深色模式；「我的」里有通知、省电无限制、小米自启动的直达入口

## 目录

```
android/        Kotlin + Compose + Room + WorkManager（包名 com.todayeat.app）
  app/src/main/java/com/todayeat/app/
    data/       Room 实体、DAO、Repository（所有写操作的入口）
    domain/     Schedule（哪天该吃、下次提醒）、Days（某天状态）、Copy（时间日期格式）
    notify/     Reminders：setAlarmClock 主提醒 + 再提醒 + 通知按钮 + 开机重排
    sync/       Crypto（PBKDF2 → 数据密钥 + 登录凭据）、SyncMerge、SyncApi、SyncManager
    photo/      照片导入、压缩、存储
    update/     应用内更新（移植自记账）
    ui/         mine（打卡 / 日历）、settings、common（记录编辑、药品编辑、登录）、components（通用控件）
server/         FastAPI + SQLite，/api/te/*（端口 8096）
web/store/      应用商店首页
web/todayeat/   吃了吗下载页
deploy/         deploy.sh、systemd、备份 cron、nginx 幂等补丁 patch_nginx.py
scripts/        adb_tap.sh：模拟器里按界面文字点击
```

## 构建、测试、发布

```bash
# 单测
(cd android && ./gradlew testDebugUnitTest)
server/.venv/bin/python -m pytest -q server/tests

# 本地联调：本机起服务端，调试包指向模拟器宿主
TODAYEAT_DATA=/tmp/te server/.venv/bin/uvicorn app:app --app-dir server --port 8096
(cd android && ./gradlew assembleDebug -PserverUrl=http://10.0.2.2:8096)

# 发布：先改 android/app/build.gradle.kts 的 versionCode / versionName
(cd android && ./gradlew assembleRelease)
cp android/app/build/outputs/apk/release/app-release.apk dist/todayeat-vX.Y.Z.apk
NOTES="这一版改了什么" deploy/deploy.sh dist/todayeat-vX.Y.Z.apk
```

`deploy.sh` 用 ssh 别名 `oracle-main`；会先备份到服务器 `~/backup/todayeat-<时间戳>/`，上传服务端、商店首页、下载页、APK，生成版本文件，幂等补 nginx，最后做健康检查。

## 签名

正式包用独立 keystore：`android/keystore/todayeat-release.jks` + `keystore.properties`（均 gitignore），备份在 `deploy/private/`。**丢了就无法覆盖升级**，请另存一份到安全的地方。

## 服务器与已知风险

- 与暖月（8090）、记账（8095）共用 nginx 站点 `/etc/nginx/sites-available/nuanyue`。吃了吗的块在 `# BEGIN todayeat … # END todayeat` 之间。
- **暖月的 `deploy.sh` 会整文件覆盖这个 nginx 配置**，吃了吗和记账加的 location 都会丢（记账原本就有这个问题）。暖月每次部署后执行：
  ```bash
  ssh oracle-main 'sudo python3 /opt/todayeat/patch_nginx.py --backup-dir ~/backup'
  ```
  记账的 location 需要按记账文档另行恢复。长期做法是把这些块并进暖月仓库的 `nginx-nuanyue.conf`。
- 根路径 `/` 现在是应用商店；暖月原首页改由 `/nuanyue.html` 访问（nginx alias，不改暖月文件）。旧版暖月 App 里的下载页链接会打开商店，商店里有暖月入口。
- 数据：`/var/lib/todayeat/`（todayeat.db + photos/），每天 03:37 备份，保留 14 天。

## 验证记录（2026-10-29，v1.0.0）

模拟器 1440×3200 / 560dpi（小米 15 Pro 分辨率）+ 本地服务端，以及正式包 + 生产服务器：

- 单测 Android 12 项、服务端 16 项通过
- 引导 → 打卡 → 拍照 → 日历 → 注册同步 → 卸载重装后登录找回记录和照片
- Python 用同样算法解密云端快照与照片，与手机上内容逐字节一致；服务器只存密文
- 真实闹钟准点触发、10 分钟再提醒已排、应用内打卡后再提醒取消、通知「已吃」入库
- 正式包（R8）对生产服务器注册与同步成功，测试账号已删除

未验证：小米真机上的锁屏/省电场景下提醒准时性、相机在真机上的拍摄效果。

## v1.1.0（2026-10-29）

- 底部只留「我的」「设置」；打卡和日历合并到「我的」，用分段切换
- 打卡改成大圆按钮 + 本周七天
- 记录支持一天多条，可添加、修改、删除；同步不再按天去重
- 去掉桌面小组件、连续天数、开始吃药日期、问候语和安慰性文案
- 新控件：滚轮选时间、带标题的输入框、分段切换、删除确认框

模拟器（1440×3200 / 560dpi）+ 本地服务端验证：引导、打卡、编辑记录（改时刻、备注）、添加第二条记录并拍照、删除记录、日历视图、注册并同步（含照片上传）、测试通知、深色模式。
