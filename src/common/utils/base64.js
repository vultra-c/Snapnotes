/**
 * Base64 解码工具
 * 将 base64 字符串转换为 ArrayBuffer，用于公式图片写入文件系统
 */

var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
var lookup = {}
for (var i = 0; i < chars.length; i++) {
  lookup[chars.charAt(i)] = i
}

/**
 * 将 base64 字符串解码为 ArrayBuffer
 * @param {string} base64 - base64 编码的字符串
 * @returns {ArrayBuffer} 解码后的二进制数据
 */
function base64ToArrayBuffer(base64) {
  if (!base64 || typeof base64 !== 'string') return null
  // 去除前缀（如 data:image/png;base64,）和 padding
  var b64 = base64.replace(/^data:[^;]+;base64,/, '').replace(/=+$/, '')
  var len = b64.length
  if (len === 0) return new ArrayBuffer(0)

  // 计算输出字节数
  var outputLen = (len * 3) >> 2
  if (b64.charAt(len - 1) === '=') outputLen--
  if (b64.charAt(len - 2) === '=') outputLen--

  var bytes = new Uint8Array(outputLen)
  var byteIdx = 0
  for (var i = 0; i < len; i += 4) {
    var b0 = lookup[b64.charAt(i)] || 0
    var b1 = lookup[b64.charAt(i + 1)] || 0
    var b2 = lookup[b64.charAt(i + 2)]
    var b3 = lookup[b64.charAt(i + 3)]

    bytes[byteIdx++] = (b0 << 2) | (b1 >> 4)
    if (b2 !== undefined && byteIdx < outputLen) {
      bytes[byteIdx++] = ((b1 & 0xf) << 4) | (b2 >> 2)
    }
    if (b3 !== undefined && byteIdx < outputLen) {
      bytes[byteIdx++] = ((b2 & 0x3) << 6) | b3
    }
  }
  return bytes.buffer
}

/**
 * 清理文件名，只保留安全字符
 * @param {string} name - 原始文件名
 * @returns {string} 安全的文件名
 */
function sanitizeFileName(name) {
  if (!name || typeof name !== 'string') return 'unknown.png'
  // 只保留字母、数字、点、下划线、连字符
  var safe = name.replace(/[^a-zA-Z0-9._-]/g, '_')
  if (!safe.endsWith('.png')) safe += '.png'
  return safe
}

export { base64ToArrayBuffer, sanitizeFileName }
export default { base64ToArrayBuffer, sanitizeFileName }
