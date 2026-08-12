# Agent Sync 反向拉取通道 — 配置指南

目标仓库：`https://github.com/vultra-c/Snapnotes-android`

## 架构

```
[Snapnotes-android 仓库]
    GitHub Actions (agent-sync)  ←——每 30 分钟拉取——  [Agent API]
        │                                              GET /api/agent/files
        │                                              Authorization: Bearer <密钥>
        ▼
    解码 base64 → 写入文件 → git commit → git push（使用 GITHUB_TOKEN，无需任何个人令牌）
```

反向拉取的原因：AI 端无法直接持有目标仓库的推送凭据，
因此改为由目标仓库的 CI 主动从 AI 提供的公网 API 取文件并提交。

---

## 第一步：开启仓库 Actions 的读写权限（一次性）

1. 打开 `https://github.com/vultra-c/Snapnotes-android/settings/actions`
2. 在 **Workflow permissions**（工作流权限）中选择 **Read and write permissions**（读写权限）
3. 勾选（可选）"Allow GitHub Actions to create and approve pull requests"
4. 点击 **Save**（保存）

> 注意：如果保持默认的只读权限，工作流提交后会报 `403` 无法推送。

## 第二步：添加两个仓库密钥（一次性）

1. 打开 `https://github.com/vultra-c/Snapnotes-android/settings/secrets/actions`
2. 点击 **New repository secret**（新建仓库密钥）：
   - Name（名称）：`AGENT_API_URL`
   - Value（值）：你的 Agent API 完整地址，例如
     `https://你的服务域名/api/agent/files`（**必须是 /api/agent/files 结尾**）
   - 点击 **Add secret**
3. 再次点击 **New repository secret**：
   - Name：`AGENT_API_KEY`
   - Value：AI 提供给你的鉴权密钥（Bearer token）
   - 点击 **Add secret**

## 第三步：放入工作流文件（一次性）

1. 在 Snapnotes-android 仓库根目录创建 `.github/workflows/` 文件夹
2. 把 AI 提供的工作流内容保存为 `.github/workflows/agent-sync.yml`
3. 提交并推送到 main 分支

## 第四步：手动触发测试（验证链路）

1. 打开 `https://github.com/vultra-c/Snapnotes-android/actions`
2. 左侧选择 **agent-sync**
3. 点击右侧 **Run workflow**（运行工作流）→ 保持分支为 main → 点击绿色按钮
4. 观察运行日志：
   - 应能看到 `已更新 example/sync-test.txt`
   - 随后 Commit and push 步骤输出提交信息
5. 刷新仓库文件列表，应出现 `example/sync-test.txt`（内容为测试文本）

验证通过后，把 `example/sync-test.txt` 从目标仓库删除，
并把 AI 侧 `agent-payload/manifest.json` 的 `files` 改为 `[]`（或放入真实要同步的文件）。

## 部署 Agent API（两种方式任选）

### 方式 A：部署到已有 Render 服务（推荐）

AI 已在 `web/` 目录实现 API（零新增依赖，和现有 RPK 预览工作台同一个服务）：

1. 用 `web/` 目录 + `render.yaml` 部署（与你之前部署 RPK 预览工作台相同）
2. 在 Render 服务页面 → Environment 中添加环境变量：
   - `AGENT_API_KEY` = AI 提供给你的鉴权密钥
3. 重新部署后，API 地址为：`https://<你的Render服务域名>/api/agent/files`
4. 用第一步的地址更新 GitHub 密钥 `AGENT_API_URL`

### 方式 B：部署到你自己的服务器 + Cloudflare Tunnel

1. 服务器上运行（需要 Node.js ≥ 16）：

```bash
cd web
npm ci            # 或跳过（本服务零依赖，直接运行也可）
AGENT_API_KEY=你的密钥 PORT=10000 node server.js
```

2. 用 Cloudflare Tunnel 暴露到公网（二选一）：

快速临时隧道（每次重启地址会变，只适合测试）：

```bash
cloudflared tunnel --url http://localhost:10000
```

固定域名隧道（推荐，地址不变）：

```yaml
# cloudflared/config.yml
tunnel: <你的隧道ID>
credentials-file: /etc/cloudflared/<隧道ID>.json

ingress:
  - hostname: agent-sync.你的域名.com
    service: http://localhost:10000
  - service: http_status:404
```

```bash
cloudflared tunnel login
cloudflared tunnel create agent-sync
cloudflared tunnel route dns agent-sync agent-sync.你的域名.com
cloudflared tunnel --config cloudflared/config.yml run agent-sync
```

3. API 地址为 `https://agent-sync.你的域名.com/api/agent/files`，更新 GitHub 密钥 `AGENT_API_URL`。

## 日常使用

- AI 端有文件要交付时：放入 `agent-payload/` → 更新 `manifest.json` → 重新部署 API
  （Render 配了 autoDeploy 时，提交即自动生效）
- 最多 30 分钟内，目标仓库会自动出现同步提交
- 想要立即生效：到 Snapnotes-android 的 Actions 页面手动触发 **agent-sync**

## 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 提交时报 403 | 仓库 Actions 权限为只读 | 检查第一步设置 |
| 日志显示 "未配置" | Secret 未添加或名称拼错 | 检查第二步 |
| 日志显示 "不可达或非 200" | API 未部署 / 地址不对 / 密钥错 | 检查部署与 AGENT_API_URL |
| 日志显示 "格式非预期" | 返回的不是 `{files: [...]}` | 确认访问的是 /api/agent/files |
| 日志显示 "没有需要更新的文件" | manifest.json 的 files 为空 | 正常行为，链路是通的 |
