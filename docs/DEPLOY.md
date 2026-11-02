# 发布与部署

## 发布

1. 改 `android/app/build.gradle.kts` 里的 `versionCode` / `versionName`
2. 打包并部署：

```bash
(cd android && ./gradlew assembleRelease)
cp android/app/build/outputs/apk/release/app-release.apk dist/todayeat-vX.Y.Z.apk
NOTES="这一版改了什么" deploy/deploy.sh dist/todayeat-vX.Y.Z.apk
```

`deploy.sh` 从 `deploy/private/site.env` 读站点地址，用 ssh 别名 `oracle-main` 连服务器。它会先把线上文件备份到 `~/backup/todayeat-<时间戳>/`，再上传服务端、网页和 APK，生成 `todayeat-version.json`，补 nginx，最后做健康检查。

## 签名

正式包用 `android/keystore/todayeat-release.jks` 和 `keystore.properties`，都不在仓库里，备份在 `deploy/private/`。丢了就没法覆盖升级。

## 服务器

- 服务：`todayeat.service`，监听 127.0.0.1:8096
- 数据：`/var/lib/todayeat/`（`todayeat.db` 和 `photos/`），每天 03:37 备份，保留 14 天
- 和暖月（8090）、记账（8095）共用 nginx 站点 `/etc/nginx/sites-available/nuanyue`，吃了吗的配置在 `# BEGIN todayeat` 和 `# END todayeat` 之间
- 根路径 `/` 是应用列表页，暖月原来的首页改到 `/nuanyue.html`
- `/etc/nginx/conf.d/todayeat-origin-guard.conf` 拦掉不经 CDN 的网页请求，只放行接口、版本文件和安装包

暖月的 `deploy.sh` 会整份覆盖 nginx 配置。它部署完会自动重跑补丁；手动补：

```bash
ssh oracle-main 'sudo python3 /opt/todayeat/patch_nginx.py --backup-dir ~/backup'
```

## 已知问题

- 打卡按钮会把当天还没到提醒时间的药一起记上
- 没在小米真机上验证过锁屏、省电模式下的提醒准时性
