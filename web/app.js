(() => {
  'use strict'

  const MAX_FILE_SIZE = 50 * 1024 * 1024
  const $ = (id) => document.getElementById(id)
  const input = $('rpkInput')
  const dropzone = $('dropzone')
  const fileChip = $('fileChip')
  const consoleBody = $('consoleBody')
  const novncUrl = $('novncUrl')
  const embeddedNote = $('embeddedNote')
  const state = { file: null, report: null, mode: 'inspect' }

  const demoManifest = {
    package: 'com.whyy.snapnotes',
    name: '闪念小抄',
    versionName: '1.0.1',
    versionCode: 101,
    deviceTypeList: ['watch'],
    features: [
      { name: 'system.router' },
      { name: 'system.storage' },
      { name: 'system.interconnect' },
      { name: 'system.file' }
    ],
    router: {
      entry: 'pages/index',
      pages: {
        'pages/index': { component: 'index' },
        'pages/subfolder': { component: 'subfolder' },
        'pages/reader': { component: 'reader' },
        'pages/content': { component: 'content' }
      }
    }
  }

  function log(message, level = 'info') {
    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    const row = document.createElement('p')
    const time = document.createElement('time')
    const tag = document.createElement('span')
    time.textContent = now
    tag.textContent = level.toUpperCase()
    tag.className = level === 'error' ? 'log-muted' : 'log-info'
    row.append(time, tag, document.createTextNode(message))
    consoleBody.appendChild(row)
    consoleBody.scrollTop = consoleBody.scrollHeight
  }

  function setText(id, value) {
    $(id).textContent = value == null || value === '' ? '—' : String(value)
  }

  function formatBytes(bytes) {
    if (!bytes) return '0 B'
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  }

  function safeText(value) {
    return value == null ? '' : String(value)
  }

  function addRow(parent, left, right, className = 'manifest-row') {
    const row = document.createElement('div')
    row.className = className
    const first = document.createElement('span')
    const second = document.createElement('span')
    first.textContent = left
    second.textContent = right
    row.append(first, second)
    parent.appendChild(row)
  }

  function clearChildren(element) {
    while (element.firstChild) element.removeChild(element.firstChild)
  }

  function setMode(mode) {
    state.mode = mode
    document.querySelectorAll('[data-mode]').forEach((button) => {
      button.classList.toggle('active', button.dataset.mode === mode)
    })
    embeddedNote.classList.toggle('hidden', mode !== 'true')
    $('previewMode').textContent = mode === 'true' ? 'TRUE RUNTIME MODE' : 'INSPECT MODE'
    $('watchSubtitle').textContent = mode === 'true' ? '真运行：在自己服务器上打开 Vela 模拟器' : '等待载入 RPK'
    log(mode === 'true' ? '已切换到真运行模式：等待 noVNC 连接' : '已切换回浏览器检查模式')
  }

  function renderManifest(manifest, entries, sourceName) {
    const packageName = manifest.name || manifest.package || sourceName.replace(/\.rpk$/i, '')
    const packageId = manifest.package || '未提供 package'
    const pages = manifest.router && manifest.router.pages ? Object.keys(manifest.router.pages) : []
    const features = Array.isArray(manifest.features) ? manifest.features.map((item) => item && item.name).filter(Boolean) : []

    setText('packageName', packageName)
    setText('packageId', packageId)
    setText('versionValue', manifest.versionName || manifest.versionCode || '—')
    setText('typeValue', (manifest.deviceTypeList || ['watch']).join(' / ').toUpperCase())
    setText('pagesValue', pages.length)
    setText('filesValue', entries.length)
    $('packageBadge').textContent = 'RPK LOADED'
    $('appAvatar').textContent = safeText(packageName).slice(0, 1).toUpperCase() || 'V'
    $('watchTitle').textContent = packageName
    $('watchSubtitle').textContent = state.mode === 'true' ? '已载入 RPK · 可推送到真模拟器' : `${pages.length} routes · inspection mode`
    $('previewMode').textContent = state.mode === 'true' ? 'TRUE RUNTIME READY' : 'INSPECT MODE'
    $('exportReport').disabled = false

    const manifestList = $('manifestList')
    clearChildren(manifestList)
    addRow(manifestList, 'package', packageId)
    addRow(manifestList, 'version', `${manifest.versionName || '—'} (${manifest.versionCode || '—'})`)
    addRow(manifestList, 'entry', manifest.router && manifest.router.entry ? manifest.router.entry : '—')
    addRow(manifestList, 'features', features.length ? `${features.length} system APIs` : '—')

    const routeList = $('routeList')
    clearChildren(routeList)
    if (!pages.length) {
      routeList.innerHTML = '<p class="empty-copy">manifest 未声明页面路由</p>'
    } else {
      pages.forEach((page) => {
        const row = document.createElement('div')
        row.className = 'route-row'
        row.innerHTML = `<span>${page}</span>`
        routeList.appendChild(row)
      })
    }

    const archiveList = $('archiveList')
    clearChildren(archiveList)
    entries.slice(0, 80).forEach((entry) => {
      const row = document.createElement('div')
      row.className = 'archive-row'
      const name = document.createElement('span')
      const size = document.createElement('span')
      name.textContent = entry.name
      size.className = 'size'
      size.textContent = formatBytes(entry.uncompressedSize)
      row.append(name, size)
      archiveList.appendChild(row)
    })
    if (entries.length > 80) {
      const more = document.createElement('p')
      more.className = 'empty-copy'
      more.textContent = `还有 ${entries.length - 80} 个文件未展开`
      archiveList.appendChild(more)
    }
    setText('fileCount', entries.length)

    const cards = $('watchCards')
    clearChildren(cards)
    const cardPages = pages.length ? pages.slice(0, 3) : ['preview/home', 'preview/files']
    cardPages.forEach((page, index) => {
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'watch-card'
      button.dataset.screen = page
      button.innerHTML = `<span>${index === 0 ? '⌂' : index === 1 ? '▤' : '◌'}</span><b>${page.split('/').pop()}</b><small>route preview only</small>`
      cards.appendChild(button)
    })

    state.report = {
      source: sourceName,
      package: packageId,
      name: packageName,
      version: manifest.versionName || manifest.versionCode || null,
      pages,
      features,
      fileCount: entries.length,
      files: entries.map((entry) => ({ name: entry.name, compressedSize: entry.compressedSize, uncompressedSize: entry.uncompressedSize }))
    }
  }

  function showFile(file) {
    state.file = file
    fileChip.classList.remove('hidden')
    setText('fileName', file.name)
    setText('fileSize', formatBytes(file.size))
    dropzone.classList.add('hidden')
  }

  function clearFile() {
    state.file = null
    state.report = null
    input.value = ''
    fileChip.classList.add('hidden')
    dropzone.classList.remove('hidden')
    $('packageBadge').textContent = 'NO RPK'
    $('exportReport').disabled = true
    log('已清除当前本地包')
  }

  function findEndOfCentralDirectory(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    const start = Math.max(0, bytes.length - 65557)
    for (let offset = bytes.length - 22; offset >= start; offset -= 1) {
      if (view.getUint32(offset, true) === 0x06054b50) return offset
    }
    throw new Error('不是有效的 ZIP/RPK 文件（缺少目录结束标记）')
  }

  function readZipEntries(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    const decoder = new TextDecoder()
    const eocd = findEndOfCentralDirectory(bytes)
    const count = view.getUint16(eocd + 10, true)
    const directorySize = view.getUint32(eocd + 12, true)
    const directoryOffset = view.getUint32(eocd + 16, true)
    if (directoryOffset + directorySize > bytes.length) throw new Error('ZIP 中央目录超出文件范围')

    const entries = []
    let offset = directoryOffset
    for (let i = 0; i < count && offset + 46 <= bytes.length; i += 1) {
      if (view.getUint32(offset, true) !== 0x02014b50) throw new Error('ZIP 中央目录损坏')
      const method = view.getUint16(offset + 10, true)
      const compressedSize = view.getUint32(offset + 20, true)
      const uncompressedSize = view.getUint32(offset + 24, true)
      const nameLength = view.getUint16(offset + 28, true)
      const extraLength = view.getUint16(offset + 30, true)
      const commentLength = view.getUint16(offset + 32, true)
      const localOffset = view.getUint32(offset + 42, true)
      const name = decoder.decode(bytes.subarray(offset + 46, offset + 46 + nameLength))
      entries.push({ name, method, compressedSize, uncompressedSize, localOffset })
      offset += 46 + nameLength + extraLength + commentLength
    }
    return entries
  }

  function getCompressedData(bytes, entry) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    const offset = entry.localOffset
    if (view.getUint32(offset, true) !== 0x04034b50) throw new Error(`文件头损坏: ${entry.name}`)
    const nameLength = view.getUint16(offset + 26, true)
    const extraLength = view.getUint16(offset + 28, true)
    const start = offset + 30 + nameLength + extraLength
    return bytes.slice(start, start + entry.compressedSize)
  }

  async function inflateRaw(data) {
    if (typeof DecompressionStream === 'undefined') {
      throw new Error('当前浏览器不支持 ZIP 解压，请使用最新版 Chrome/Edge')
    }
    const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
    return new Uint8Array(await new Response(stream).arrayBuffer())
  }

  async function readEntry(bytes, entry) {
    const compressed = getCompressedData(bytes, entry)
    if (entry.method === 0) return compressed
    if (entry.method === 8) return inflateRaw(compressed)
    throw new Error(`暂不支持 ${entry.name} 的 ZIP 压缩方式（${entry.method}）`)
  }

  async function decodeJson(bytes, entry) {
    const data = await readEntry(bytes, entry)
    return JSON.parse(new TextDecoder().decode(data))
  }

  async function inspectRpk(file) {
    if (file.size > MAX_FILE_SIZE) throw new Error('文件超过 50 MB 限制')
    const bytes = new Uint8Array(await file.arrayBuffer())
    const entries = readZipEntries(bytes)
    if (!entries.length) throw new Error('RPK 内没有可读取的文件')
    const manifestEntry = entries.find((entry) => entry.name === 'manifest-watch.json') || entries.find((entry) => entry.name === 'manifest.json') || entries.find((entry) => /(^|\/)manifest(-watch)?\.json$/.test(entry.name))
    if (!manifestEntry) throw new Error('RPK 内没有 manifest.json 或 manifest-watch.json')
    const manifest = await decodeJson(bytes, manifestEntry)
    return { manifest, entries }
  }

  async function handleFile(file) {
    if (!file) return
    if (!/\.rpk$/i.test(file.name)) {
      log('请选择 .rpk 文件', 'error')
      return
    }
    showFile(file)
    log(`开始读取 ${file.name}（${formatBytes(file.size)}）`)
    try {
      const result = await inspectRpk(file)
      renderManifest(result.manifest, result.entries, file.name)
      log(`manifest 解析完成：${result.manifest.package || result.manifest.name || 'unknown'}`)
      log(`发现 ${result.entries.length} 个归档文件，已进入检查模式`)
      if (state.mode === 'true') log('如已启动真模拟器，可用 aiot/emulator 通道把该包安装进模拟器')
    } catch (error) {
      log(error.message || 'RPK 解析失败', 'error')
      $('packageBadge').textContent = 'PARSE ERROR'
    }
  }

  function loadDemo() {
    renderManifest(demoManifest, [
      { name: 'manifest.json', uncompressedSize: 1720 },
      { name: 'manifest-watch.json', uncompressedSize: 1320 },
      { name: 'app.js', uncompressedSize: 8420 },
      { name: 'pages/index/index.js', uncompressedSize: 4610 },
      { name: 'common/style.css', uncompressedSize: 2190 },
      { name: 'common/images/icon.png', uncompressedSize: 18340 }
    ], 'snapnotes-demo.rpk')
    showFile({ name: 'snapnotes-demo.rpk', size: 36742 })
    log('载入工作台示例：这是本地浏览器检查模式')
    log('切换到真运行模式后，可连接你服务器上的 noVNC 真模拟器')
  }

  function exportReport() {
    if (!state.report) return
    const blob = new Blob([JSON.stringify(state.report, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${state.report.package || 'vela-app'}-report.json`
    link.click()
    URL.revokeObjectURL(url)
    log('已导出包结构报告')
  }

  dropzone.addEventListener('dragover', (event) => { event.preventDefault(); dropzone.classList.add('dragging') })
  dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragging'))
  dropzone.addEventListener('drop', (event) => {
    event.preventDefault()
    dropzone.classList.remove('dragging')
    handleFile(event.dataTransfer.files[0])
  })
  input.addEventListener('change', () => handleFile(input.files[0]))
  $('clearFile').addEventListener('click', clearFile)
  $('loadDemo').addEventListener('click', loadDemo)
  $('exportReport').addEventListener('click', exportReport)
  $('clearConsole').addEventListener('click', () => { consoleBody.innerHTML = ''; log('控制台已清空') })
  $('reloadPreview').addEventListener('click', () => log(state.mode === 'true' ? '真运行模式已刷新：请确认服务器模拟器仍在运行' : '预览状态已刷新；当前为静态检查模式'))
  $('openNovnc').addEventListener('click', () => {
    const url = novncUrl.value.trim()
    if (!url) {
      log('请先填入你服务器上的 noVNC 地址', 'error')
      return
    }
    window.open(url, '_blank', 'noopener')
    log(`已打开真运行视图：${url}`)
  })
  document.querySelectorAll('[data-mode]').forEach((button) => {
    button.addEventListener('click', () => setMode(button.dataset.mode))
  })
  $('watchCards').addEventListener('click', (event) => {
    const card = event.target.closest('.watch-card')
    if (!card) return
    $('watchSubtitle').textContent = `${card.dataset.screen} · ${state.mode === 'true' ? 'true runtime preview' : 'static route preview'}`
    log(`切换到路由 ${card.dataset.screen}`)
  })

  fetch('/api/health').then((response) => response.json()).then((data) => {
    if (data.ok) $('serverStatus').textContent = 'WEB SERVICE ONLINE'
  }).catch(() => { $('serverStatus').textContent = 'LOCAL FILE MODE' })
})()
