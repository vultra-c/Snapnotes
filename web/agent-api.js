'use strict'

// Agent Sync API — 反向拉取通道的 HTTP 接口。
//
// 用途：GitHub Actions（位于 vultra-c/Snapnotes-android 仓库）定时调用本接口，
// 获取需要写入目标仓库的文件列表（base64 编码），解码后自动提交推送。
//
// 接口：GET /api/agent/files
// 鉴权：Authorization: Bearer <AGENT_API_KEY>
// 返回：{ "files": [ { "path": "相对路径", "content": "<base64>", "encoding": "base64" } ] }
//
// 文件来源：同目录下 agent-payload/ 文件夹。agent-payload/manifest.json 显式列出
// 需要对外提供的文件相对路径（相对于 agent-payload/ 目录）。未提供 manifest 时，
// 默认扫描整个 agent-payload/ 目录（跳过 . 开头的隐藏文件）。

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const PAYLOAD_DIR = path.join(__dirname, 'agent-payload')
const MAX_FILE_BYTES = 5 * 1024 * 1024 // 单文件上限 5MB

function timingSafeEqual(a, b) {
  const bufA = Buffer.from(String(a))
  const bufB = Buffer.from(String(b))
  if (bufA.length !== bufB.length) return false
  return crypto.timingSafeEqual(bufA, bufB)
}

function readManifest() {
  const manifestPath = path.join(PAYLOAD_DIR, 'manifest.json')
  let raw
  try {
    raw = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  } catch {
    return null
  }
  if (Array.isArray(raw)) return raw.filter((item) => typeof item === 'string')
  if (raw && typeof raw === 'object' && Array.isArray(raw.files)) {
    return raw.files.filter((item) => typeof item === 'string')
  }
  return null
}

// 将 manifest 中的相对路径安全地解析为 agent-payload 下的绝对路径，防目录穿越。
function safeJoin(relPath) {
  const target = path.resolve(PAYLOAD_DIR, relPath)
  if (target !== PAYLOAD_DIR && !target.startsWith(PAYLOAD_DIR + path.sep)) return null
  return target
}

function walkDir(dir, onFile, prefix) {
  let entries
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return
  }
  entries.sort((a, b) => a.name.localeCompare(b.name))
  for (const entry of entries) {
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name
    const abs = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walkDir(abs, onFile, rel)
    } else if (entry.isFile()) {
      if (rel.split('/').some((part) => part.startsWith('.'))) continue
      let stat
      try {
        stat = fs.statSync(abs)
      } catch {
        continue
      }
      onFile(rel, abs, stat)
    }
  }
}

function loadPayload() {
  const files = []
  const skipped = []
  const manifest = readManifest()

  if (manifest !== null) {
    // 显式清单模式：只提供 manifest.json 中列出的文件。
    for (const rel of manifest) {
      const abs = safeJoin(rel)
      let stat
      if (!abs) {
        skipped.push(rel)
        continue
      }
      try {
        stat = fs.statSync(abs)
      } catch {
        skipped.push(rel)
        continue
      }
      if (!stat.isFile()) {
        skipped.push(rel)
        continue
      }
      if (stat.size > MAX_FILE_BYTES) {
        skipped.push(rel)
        continue
      }
      files.push({ rel, abs })
    }
  } else {
    // 无清单模式：扫描全部文件。
    walkDir(PAYLOAD_DIR, (rel, abs, stat) => {
      if (stat.size > MAX_FILE_BYTES) {
        skipped.push(rel)
        return
      }
      files.push({ rel, abs })
    })
  }
  return { files, skipped }
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  })
  res.end(payload)
}

function handleAgentApi(req, res) {
  if (req.method !== 'GET') {
    sendJson(res, 405, { error: 'Method Not Allowed' })
    return
  }

  const expected = process.env.AGENT_API_KEY
  if (!expected) {
    sendJson(res, 503, { error: 'server not configured: AGENT_API_KEY env missing' })
    return
  }

  const header = req.headers.authorization || ''
  const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : ''
  if (!token || !timingSafeEqual(token, expected)) {
    sendJson(res, 401, { error: 'Unauthorized' })
    return
  }

  const { files, skipped } = loadPayload()
  const payload = files.map(({ rel, abs }) => ({
    path: rel,
    content: fs.readFileSync(abs).toString('base64'),
    encoding: 'base64'
  }))

  sendJson(res, 200, {
    service: 'agent-sync-api',
    count: payload.length,
    files: payload,
    skipped,
    generatedAt: new Date().toISOString()
  })
}

module.exports = { handleAgentApi, PAYLOAD_DIR }
