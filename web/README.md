# VelaScope RPK Preview Lab

本仓库在原来的 Vela 快应用之外新增一个独立网页工作台，不改动 `src/` 下的手环应用运行时。目标是同时提供两条可用链路：一个可立刻部署到 Render 的浏览器工作台，和一个在你自己服务器上达到官方 IDE 体验的真运行扩展。

## 1. Render 上的浏览器工作台（已可运行）

这是你当前已经可以上传 `.rpk` 并预览的版本：

- 本地解析 `manifest.json` / `manifest-watch.json`、包信息、路由、归档文件
- 336×480 手环视口、路由卡片、控制台、报告导出
- 部署配置在 `render.yaml`

```bash
npm ci
npm run web:start
```

打开 `http://localhost:10000`，健康检查为 `/api/health`。

Render 配置：

- Install command：`npm ci`
- Start command：`npm run web:start`
- Health check：`/api/health`

## 2. 你服务器上的真运行（和官方 IDE 一样可交互）

官方 Vela 模拟器基于 `@aiot-toolkit/emulator`、`@aiot-toolkit/velasim`、`system-images`、QEMU/NuttX、QuickJS 和 `@system.*` 原生 API，必须运行在带图形栈的 Linux 主机里，不能只靠 Render 的 Node Web Service 完成。

本仓库已加入可选的自托管扩展：

- `docker/vela-emulator/Dockerfile`
- `docker/vela-emulator/docker-compose.yml`
- `docker/vela-emulator/entrypoint.sh`
- `scripts/setup-emulator.sh`
- `docs/true-runtime.md`

在你的服务器上：

```bash
docker compose -f docker/vela-emulator/docker-compose.yml up -d --build
docker compose -f docker/vela-emulator/docker-compose.yml exec vela-emulator bash -lc "./scripts/setup-emulator.sh"
```

之后在网页的 `TRUE RUNTIME` 输入框填入：

```
https://your-server:6080/vnc.html?autoconnect=1&resize=scale
```

即可通过浏览器操作真模拟器窗口。

## 为什么要分成两层

- Render 适合托管无状态网页工作台，不适合长期运行 QEMU 窗口和 ADB/gRPC 会话
- 真运行需要持久进程、较大的镜像文件和图形/VNC 能力，更适合放在你自己的服务器
- 分层后，你既有可分享的线上页面，也有本地真机级调试能力
