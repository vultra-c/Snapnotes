/**
 * 考点阅读器 - 结构化内容解析器
 *
 * 支持「闪念小抄」JSON 格式，将 JSON 文本转换为带样式的结构化渲染块。
 *
 * === JSON 格式规范（参考闪念小抄）===
 *
 * 文件是一个 JSON 对象，用「科目名」当 key，后面跟着这个科目的所有条目数组。
 *
 * 最简单的写法：
 * {
 *   "我的笔记": [
 *     { "title": "勾股定理" }
 *   ]
 * }
 *
 * 一条条目里能写什么：
 *   title（标题）：必填，没有会被丢弃
 *   desc（简介）：可选，显示在标题下方
 *   raw（原文）：可选，整段原文，按行分段、按字数分页显示
 *   points（要点）：可选，字符串数组，每条是一条速记要点
 *   formulas（公式）：可选，字符串数组（如果有对应公式图则显示图片，否则文字显示）
 *   formulaImages（公式图文件名）：可选，字符串数组，与 formulas 一一对应
 *   id（编号）：可选，缺了用顺序号
 *
 * 推荐完整写法：
 * {
 *   "拓展物理": [
 *     {
 *       "id": 1,
 *       "title": "相对论初步",
 *       "desc": "狭义相对论的基本假设与时间膨胀效应。",
 *       "raw": "狭义相对论建立在两条基本假设之上......",
 *       "points": ["光速不变原理", "相对性原理", "运动钟变慢"],
 *       "formulas": ["E=mc²"]
 *     }
 *   ]
 * }
 *
 * === 默认配色 ===
 * 学科徽章:  #3B6BB0 (蓝)
 * 原文:      #C9A86A (金)
 * 要点:      #7899BB (蓝)
 * 公式:      #9B7EBD (紫)
 * 描述:      rgba(255,255,255,0.4) (灰)
 * 正文:      #E8E8EC (浅灰白)
 *
 * === 渲染块类型 ===
 * subject       - 学科徽章（带颜色的圆角矩形）
 * title         - 标题（大号白色粗体）
 * desc          - 描述（灰色小字）
 * divider       - 分隔线（细微白色线条）
 * section_label - 模块标签（原文/要点/公式等，带颜色方框）
 * paragraph     - 段落文本
 * item_title    - 带序号的要点标题
 * item_content  - 要点内容
 * formula_image - 公式图片（PNG 渲染的 LaTeX 公式）
 */

// 默认配色
var SUBJECT_BADGE_COLOR = '#3B6BB0'
var COLOR_RAW = '#C9A86A'       // 原文 - 金色
var COLOR_POINTS = '#7899BB'    // 要点 - 蓝色
var COLOR_FORMULAS = '#9B7EBD' // 公式 - 紫色
var COLOR_DESC = 'rgba(255,255,255,0.4)'
var COLOR_TEXT = '#E8E8EC'
var DEFAULT_FONT_SIZE = 26

/**
 * 检测文本是否为结构化 JSON 格式
 * 闪念小抄格式：以 { 开头的 JSON 对象，包含科目名作为 key
 * @param {string} text - 原始文本
 * @returns {boolean}
 */
function isStructuredContent(text) {
  if (!text || text.length === 0) return false
  // 去除前导空白
  var trimmed = text.replace(/^\s+/, '')
  // JSON 对象以 { 开头
  if (trimmed.charAt(0) !== '{') return false
  // 尝试解析验证是否为有效 JSON
  try {
    var obj = JSON.parse(text)
    // 必须是对象（不是数组）
    if (typeof obj !== 'object' || obj === null || Array.isArray(obj)) return false
    // 至少有一个 key
    var hasKey = false
    for (var k in obj) {
      hasKey = true
      break
    }
    return hasKey
  } catch (e) {
    return false
  }
}

/**
 * 解析 JSON 格式文本为结构化数据
 * @param {string} text - JSON 文本
 * @returns {Object} 解析后的结构化数据 { subjects: [...] }
 */
function parseStructuredContent(text) {
  var result = {
    subjects: []
  }

  if (!text || text.length === 0) {
    return result
  }

  var obj
  try {
    obj = JSON.parse(text)
  } catch (e) {
    // JSON 解析失败，返回空
    return result
  }

  if (typeof obj !== 'object' || obj === null || Array.isArray(obj)) {
    return result
  }

  // 遍历每个科目
  for (var subjectName in obj) {
    if (!obj.hasOwnProperty(subjectName)) continue
    var entries = obj[subjectName]
    if (!Array.isArray(entries)) continue

    var subject = {
      name: subjectName,
      entries: []
    }

    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i]
      if (typeof entry !== 'object' || entry === null) continue

      // title 必填，没有则跳过
      var title = entry.title
      if (!title || typeof title !== 'string' || title.trim().length === 0) continue

      var parsedEntry = {
        id: (typeof entry.id === 'number' && entry.id > 0) ? entry.id : (subject.entries.length + 1),
        title: title.trim(),
        desc: '',
        raw: '',
        points: [],
        formulas: [],
        formulaImages: []
      }

      // desc（可选）
      if (typeof entry.desc === 'string' && entry.desc.length > 0) {
        parsedEntry.desc = entry.desc.trim()
      }

      // raw（可选）
      if (typeof entry.raw === 'string' && entry.raw.length > 0) {
        parsedEntry.raw = entry.raw
      }

      // points（可选，必须是数组）
      if (Array.isArray(entry.points)) {
        for (var p = 0; p < entry.points.length; p++) {
          if (typeof entry.points[p] === 'string' && entry.points[p].length > 0) {
            parsedEntry.points.push(entry.points[p])
          }
        }
      }

      // formulas（可选，必须是数组）
      if (Array.isArray(entry.formulas)) {
        for (var f = 0; f < entry.formulas.length; f++) {
          if (typeof entry.formulas[f] === 'string' && entry.formulas[f].length > 0) {
            parsedEntry.formulas.push(entry.formulas[f])
          }
        }
      }

      // formulaImages（可选，公式图文件名数组，与 formulas 一一对应）
      if (Array.isArray(entry.formulaImages)) {
        for (var fi = 0; fi < entry.formulaImages.length; fi++) {
          if (typeof entry.formulaImages[fi] === 'string' && entry.formulaImages[fi].length > 0) {
            parsedEntry.formulaImages.push(entry.formulaImages[fi])
          }
        }
      }

      subject.entries.push(parsedEntry)
    }

    if (subject.entries.length > 0) {
      result.subjects.push(subject)
    }
  }

  return result
}

/**
 * 将结构化数据转换为渲染块列表
 * 用于分段加载：每个块是一个独立的渲染单元
 * @param {Object} structured - parseStructuredContent 的返回值
 * @param {number} fontSize - 默认字号
 * @returns {Array} 渲染块列表
 */
function structuredToBlocks(structured, fontSize) {
  var blocks = []
  var fs = fontSize || DEFAULT_FONT_SIZE

  if (!structured || !structured.subjects || structured.subjects.length === 0) {
    return blocks
  }

  for (var si = 0; si < structured.subjects.length; si++) {
    var subject = structured.subjects[si]

    // 学科徽章
    blocks.push({
      type: 'subject',
      text: subject.name,
      color: SUBJECT_BADGE_COLOR
    })

    // 头部分隔线
    blocks.push({ type: 'divider' })

    // 遍历条目
    for (var ei = 0; ei < subject.entries.length; ei++) {
      var entry = subject.entries[ei]

      // 标题
      blocks.push({
        type: 'title',
        text: entry.title,
        fontSize: 34
      })

      // 描述
      if (entry.desc) {
        blocks.push({
          type: 'desc',
          text: entry.desc,
          color: COLOR_DESC,
          fontSize: 22
        })
      }

      // 编号标识（小字显示在标题下方）
      blocks.push({
        type: 'desc',
        text: '#' + entry.id,
        color: 'rgba(255,255,255,0.25)',
        fontSize: 20
      })

      // 原文模块
      if (entry.raw && entry.raw.length > 0) {
        blocks.push({
          type: 'section_label',
          text: '原文',
          color: COLOR_RAW
        })

        // 将原文按行分段
        var rawLines = entry.raw.split('\n')
        var paragraphBuffer = ''
        for (var ri = 0; ri < rawLines.length; ri++) {
          var rawLine = rawLines[ri]
          if (rawLine.trim().length === 0) {
            // 空行：结束当前段落
            if (paragraphBuffer.length > 0) {
              blocks.push({
                type: 'paragraph',
                text: paragraphBuffer,
                color: COLOR_TEXT,
                fontSize: fs
              })
              paragraphBuffer = ''
            }
          } else {
            if (paragraphBuffer.length > 0) {
              paragraphBuffer += '\n'
            }
            paragraphBuffer += rawLine
          }
        }
        // 最后一段
        if (paragraphBuffer.length > 0) {
          blocks.push({
            type: 'paragraph',
            text: paragraphBuffer,
            color: COLOR_TEXT,
            fontSize: fs
          })
        }
      }

      // 要点模块
      if (entry.points.length > 0) {
        blocks.push({
          type: 'section_label',
          text: '要点',
          color: COLOR_POINTS
        })

        for (var pi = 0; pi < entry.points.length; pi++) {
          blocks.push({
            type: 'item_title',
            number: pi + 1,
            text: entry.points[pi],
            color: COLOR_POINTS,
            fontSize: fs
          })
        }
      }

      // 公式模块
      if (entry.formulas.length > 0) {
        blocks.push({
          type: 'section_label',
          text: '公式',
          color: COLOR_FORMULAS
        })

        for (var fi = 0; fi < entry.formulas.length; fi++) {
          // 如果有对应的公式图文件名，生成 formula_image 块（显示 PNG 图片）
          if (fi < entry.formulaImages.length && entry.formulaImages[fi]) {
            blocks.push({
              type: 'formula_image',
              src: 'internal://files/' + entry.formulaImages[fi],
              text: entry.formulas[fi],  // 备用文本（图片加载失败时回退显示）
              color: COLOR_FORMULAS,
              fontSize: fs
            })
          } else {
            // 没有公式图，文字显示
            blocks.push({
              type: 'paragraph',
              text: entry.formulas[fi],
              color: COLOR_FORMULAS,
              fontSize: fs
            })
          }
        }
      }

      // 条目之间的分隔线（最后一个不加）
      if (ei < subject.entries.length - 1) {
        blocks.push({ type: 'divider' })
      }
    }

    // 科目之间的分隔线（最后一个不加）
    if (si < structured.subjects.length - 1) {
      blocks.push({ type: 'divider' })
    }
  }

  return blocks
}

/**
 * 将渲染块列表分段，用于分段加载
 * @param {Array} blocks - structuredToBlocks 的返回值
 * @param {number} blocksPerSegment - 每段块数（默认 15）
 * @returns {Array<Array>} 分段后的块列表
 */
function segmentBlocks(blocks, blocksPerSegment) {
  var bps = blocksPerSegment || 15
  var segments = []
  for (var i = 0; i < blocks.length; i += bps) {
    segments.push(blocks.slice(i, Math.min(i + bps, blocks.length)))
  }
  return segments.length > 0 ? segments : [[]]
}

export default {
  isStructuredContent: isStructuredContent,
  parseStructuredContent: parseStructuredContent,
  structuredToBlocks: structuredToBlocks,
  segmentBlocks: segmentBlocks,
  SUBJECT_BADGE_COLOR: SUBJECT_BADGE_COLOR,
  DEFAULT_FONT_SIZE: DEFAULT_FONT_SIZE
}
