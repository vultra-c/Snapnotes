# agent-payload — 反向拉取通道的文件源

本目录是「Agent Sync API」（`web/agent-api.js`）对外提供文件的唯一来源。
GitHub Actions 在 `vultra-c/Snapnotes-android` 仓库中每 30 分钟调用一次
`GET /api/agent/files`，取回本目录中的文件（base64 编码）并自动提交推送。

## 使用方式

1. 把需要写入 Snapnotes-android 仓库的文件放进本目录（保持相对路径结构）。
2. 编辑 `manifest.json`，把每个文件的相对路径（相对于本目录）加入 `files` 数组。
   - 例如要推送 `app/src/main/java/.../Foo.kt`，就把文件放到
     `agent-payload/app/src/main/java/.../Foo.kt`，并在 manifest 中列出该路径。
3. 部署后等待最多 30 分钟（或到目标仓库 Actions 页面手动触发），
   文件会被自动解码并提交到 Snapnotes-android。

## manifest.json 说明

- 只提供 `files` 数组中列出的文件；`README.md`、`.gitkeep` 等未列出的文件不会被提供。
- 没有 `files` 字段时（或没有 manifest.json），会扫描提供整个目录（跳过 `.` 开头文件）。
- `files: []` 时 API 返回空列表，Actions 会优雅退出，不做任何提交。
- 路径必须是相对路径；绝对路径、`..` 等会被服务端拒绝。

## 测试

本地验证（无需启动服务器，直接调用处理函数）：

```bash
cd web
AGENT_API_KEY=你的密钥 node -e '
const { handleAgentApi } = require("./agent-api")
const http = require("node:http")
const res = { status: 0, writeHead(s, h) { this.status = s }, end(b) { this.body = b } }
handleAgentApi({ method: "GET", headers: { authorization: "Bearer " + process.env.AGENT_API_KEY } }, res)
console.log(res.status, String(res.body).slice(0, 200))
'
```

完整启动服务：`AGENT_API_KEY=你的密钥 node web/server.js`，然后：

```bash
curl -H "Authorization: Bearer 你的密钥" http://localhost:10000/api/agent/files
```
