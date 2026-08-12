const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const { handleAgentApi, PAYLOAD_DIR } = require('./agent-api')

const PORT = Number(process.env.PORT) || 10000
const HOST = '0.0.0.0'
const WEB_ROOT = __dirname

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
}

function send(res, status, body, contentType) {
  res.writeHead(status, {
    'Content-Type': contentType || 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  })
  res.end(body)
}

function resolveStaticPath(urlPath) {
  const cleanPath = decodeURIComponent((urlPath || '/').split('?')[0])
  const relativePath = cleanPath === '/' ? 'index.html' : cleanPath.replace(/^\/+/, '')
  const filePath = path.resolve(WEB_ROOT, relativePath)
  if (!filePath.startsWith(WEB_ROOT + path.sep)) return null
  // agent-payload 是敏感目录（对外推送的文件），禁止匿名静态访问。
  if (filePath === PAYLOAD_DIR || filePath.startsWith(PAYLOAD_DIR + path.sep)) return null
  return filePath
}

const server = http.createServer((req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    send(res, 405, 'Method Not Allowed')
    return
  }

  const requestPath = (req.url || '/').split('?')[0]
  if (requestPath === '/api/health') {
    send(res, 200, JSON.stringify({ ok: true, service: 'vela-rpk-preview' }), MIME_TYPES['.json'])
    return
  }

  // Agent Sync API：被 Snapnotes-android 的 GitHub Actions 定时拉取。
  if (requestPath === '/api/agent/files') {
    handleAgentApi(req, res)
    return
  }

  const filePath = resolveStaticPath(requestPath)
  if (!filePath) {
    send(res, 400, 'Bad Request')
    return
  }

  fs.stat(filePath, (statError, stat) => {
    if (!statError && stat.isFile()) {
      const extension = path.extname(filePath).toLowerCase()
      res.writeHead(200, {
        'Content-Type': MIME_TYPES[extension] || 'application/octet-stream',
        'Cache-Control': extension === '.html' ? 'no-store' : 'public, max-age=300',
        'X-Content-Type-Options': 'nosniff'
      })
      if (req.method === 'HEAD') {
        res.end()
        return
      }
      fs.createReadStream(filePath).on('error', () => {
        if (!res.headersSent) send(res, 500, 'Internal Server Error')
        else res.destroy()
      }).pipe(res)
      return
    }

    // Keep browser refreshes inside the single-page workbench.
    if (requestPath !== '/index.html') {
      const indexPath = path.join(WEB_ROOT, 'index.html')
      fs.createReadStream(indexPath).on('error', () => send(res, 404, 'Not Found')).pipe(res)
      return
    }
    send(res, 404, 'Not Found')
  })
})

server.listen(PORT, HOST, () => {
  console.log(`Vela RPK preview listening on http://${HOST}:${PORT}`)
})
