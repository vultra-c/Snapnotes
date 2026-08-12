# 真运行扩展：在你自己的服务器上跑官方 Vela 模拟器

`web/` 当前版本是一个可运行的浏览器工作台：本地 RPK 检查、manifest/路由/资源浏览、336×480 预览壳、报告导出、已验证的 Render 部署配置。

要达到和小米官方 IDE 一模一样的真运行，需要在你那台服务器上额外启动以下链路。官方模拟器基于 `@aiot-toolkit/emulator`、`@aiot-toolkit/velasim`、`emulator` 二进制、`system-images` 和 QEMU/NuttX 运行时，必须运行在带图形能力的主机里，不能直接塞进 Render 的 Node Web Service。

## 方案：Docker + noVNC

```
你服务器
├── Vela workload 容器
│   ├── @aiot-sdk (emulator / skins / system-images / vvd)
│   ├── QEMU / NuttX / Vela OS / QuickJS
│   ├── aiot build 产物 -> RPK
│   └── VNC/noVNC 映射端口
└── web 预览页（当前仓库）
    ├── inspect 模式：浏览器内解析 RPK
    └── true 模式：嵌入 noVNC iframe，远程操控真模拟器
```

可用参考的开源实现思路：

- 端内已有能力：`node_modules/@aiot-toolkit/emulator` 负责下载 `vela-miwear-watch-*` 镜像、创建 `VvdManager`、启动 `emulator -vela -avd ... -grpc ... -qemu ...`
- 外部参考：`buttmo/docker-android`、`open-vela/docs` 的 QEMU/noVNC 形态
- 关键是在自托管主机上提供 X/VNC + `novnc` + `websockify` + emulator QEMU 窗口

## 最小 compose 示例

```yaml
services:
  vela-emulator:
    build:
      context: ./docker/vela-emulator
      dockerfile: Dockerfile
    ports:
      - "6080:6080"
      - "5900:5900"
      - "5555:5555"
    volumes:
      - vela-sdk:/root/.vela/sdk
      - vela-vvd:/root/.vela/vvd
      - ./:/workspace
    shm_size: 2gb

volumes:
  vela-sdk:
  vela-vvd:
```

对应 `docker/vela-emulator/Dockerfile` 已包含在仓库中。

## 运行流程

```bash
# 首次
docker compose -f docker/vela-emulator/docker-compose.yml up -d --build
docker compose -f docker/vela-emulator/docker-compose.yml exec vela-emulator bash -lc "./scripts/setup-emulator.sh"

# 之后
docker compose -f docker/vela-emulator/docker-compose.yml exec vela-emulator bash -lc "npx aiot build && npx aiot --help"
```

然后在预览页的 `TRUE RUNTIME` 输入框填入：

```
https://your-server:6080/vnc.html?autoconnect=1&resize=scale
```

并打开。

## 为什么不能只靠 Render

- 官方 emulator 需要 QEMU、图形栈、持久进程和较大的 `system.img/data.img/vela_data.bin`
- Render Web Service 属于无状态 Node 服务，不适合长期运行 QEMU 窗口和 ADB/gRPC 会话
- 正确分工是：Render 负责浏览器工作台，真运行交给你的自托管主机
