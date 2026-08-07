/**
 * 闪念小抄 - 知识点数据中枢（同步接口）
 *
 * 设计：把"读数据"集中到一处，对外只暴露同步接口，4 页改动最小。
 *   - builtin：内置 159 条，来源仍是 ./knowledgeData（本轮不动）
 *   - userSubjects：用户导入数据的缓存（三态：null=未加载 / 已加载的对象）
 *
 * 用户数据由 app.ux onCreate 时 @system.storage.get 异步预读后注入（setUserData）。
 * 页面 onInit 时通常缓存已就绪；极端竞态下用户科目稍后出现，不阻塞内置显示。
 *
 * 用户数据 JSON 结构（与内置同构）：
 *   { "<科目名>": [ { id, title, desc, points, raw?, formulas? }, ... ], ... }
 *
 * 合并 / 去重规则：
 *   - 同名科目：用户条目按 id 去重，与内置同 id 冲突时用户跳过（内置不可被覆盖）；
 *     不冲突的用户条目追加到内置 list 末尾。
 *   - 全新科目（内置无）：整科追加，imported=true，排在内置之后。
 *   - 校验失败（JSON 非法 / 结构不符）：setUserData 静默丢弃，userSubjects 置 {}，内置照常显示。
 */

import builtin from './knowledgeData'
import extrasSample from './extras/sample'
import pointIndex from './formulas/_point_index'

/**
 * 预制扩展包注册表：包名 → 已 import 的 JS 对象。
 * 走 import .js 而非 @system.file.readText，因后者在真机对应用资源路径直接报 202（doc 与实机脱节）。
 * import 是知识数据 knowledgeData.js 已验证可用的加载路，真机零风险。
 * 后期新增扩展包：在 extras/ 下建 xxx.js，加一行 import + 在此注册即可。
 */
const extrasRegistry = {
  'sample': extrasSample
}

// 用户数据缓存。null 表示尚未被 app.ux 注入过（按内置显示）。
let userSubjects = null

// 用户公式图片索引：key = "科目名#id"，value = { file, w, h }（file 为 internal://files/formulas/ 下的文件名）。
// 内置公式图走 pointIndex（随包资源），用户推送的公式图走这里（运行时接收 + index.json 持久化）。
// 由 app.ux 启动时从 internal://files/formulas/index.json 读回（setUserFormulaIndex 合并）。
let userFormulaIndex = {}

// 复用：内置科目名顺序（保证内置科目置顶、顺序稳定）
const builtinNames = Object.keys(builtin)

/**
 * 注入用户数据。由 app.ux onCreate 调用（重置式：以 storage 持久化内容为唯一来源）。
 * 非法数据静默丢弃。
 * @param {string} jsonStr 来自 storage 的字符串
 */
function setUserData(jsonStr) {
  userSubjects = {}
  if (!jsonStr) return
  let parsed = null
  try {
    parsed = JSON.parse(jsonStr)
  } catch (e) {
    return
  }
  mergeParsedInto(parsed)
}

/**
 * 把一个已解析的对象并入 userSubjects（增量式：不重置已有用户数据，用于动态加载 extras 包）。
 * 内部纯函数，xlsx parse 失败的 JSON 不调用它。
 * @param {object} parsed JSON.parse 成功的对象
 */
function mergeParsedInto(parsed) {
  if (!parsed || typeof parsed !== 'object') return
  if (!userSubjects) userSubjects = {}
  const names = Object.keys(parsed)
  for (let i = 0; i < names.length; i++) {
    const name = names[i]
    const list = parsed[name]
    if (!Array.isArray(list)) continue
    const clean = []
    for (let j = 0; j < list.length; j++) {
      const it = list[j]
      if (!it || typeof it !== 'object') continue
      if (!it.title || typeof it.title !== 'string') continue
      clean.push({
        id: (typeof it.id === 'number') ? it.id : (j + 1),
        title: it.title,
        desc: (it.desc && typeof it.desc === 'string') ? it.desc : '',
        raw: (it.raw && typeof it.raw === 'string') ? it.raw : '',
        points: Array.isArray(it.points) ? it.points.filter(p => typeof p === 'string') : [],
        formulas: Array.isArray(it.formulas) ? it.formulas.filter(f => typeof f === 'string') : []
      })
    }
    if (!clean.length) continue
    // 增量合并：同名科目把新条目按 id 追加去重，不覆盖已有条目
    if (userSubjects[name] && userSubjects[name].length) {
      const existing = {}
      for (let k = 0; k < userSubjects[name].length; k++) existing[userSubjects[name][k].id] = true
      for (let m = 0; m < clean.length; m++) {
        if (!existing[clean[m].id]) userSubjects[name].push(clean[m])
      }
    } else {
      userSubjects[name] = clean
    }
  }
}

/**
 * 加载一个预制扩展包并合并进 userSubjects。
 * 走静态 import（extrasRegistry），不再用 @system.file.readText（真机 202）。
 * 保留 (name, cb) 签名是与 detail 调用兼容；实际是同步取+合并后异步调 cb。
 * @param {string} pkgName 不含扩展名的包名，如 'sample'
 * @param {function} cb 回调 cb({ok:boolean, reason?:string, path?:string})
 */
function loadExtraByName(pkgName, cb) {
  const pkg = extrasRegistry[pkgName]
  if (!pkg) {
    cb && cb({ ok: false, reason: 'no-reg' })
    return
  }
  // extrasRegistry 里都是已 import 成功的 JS 对象，结构已是合法 object；仍过一遍 merge 兜底
  mergeParsedInto(pkg)
  cb && cb({ ok: true })
}


/**
 * 把当前用户数据缓存 userSubjects 整体序列化成 JSON 字符串。
 * 用于 interconnect 收到新科目并入后,把"全量用户数据快照"落盘 internal://files/ 持久化
 * (落单条消息会覆盖既有,所以必须落当前全量)。无用户数据时返回 ''。
 * @returns {string}
 */
function getUserDataJSON() {
  if (!userSubjects) return ''
  return JSON.stringify(userSubjects)
}

/**
 * 把用户公式图片索引并入 userFormulaIndex（增量式：不重置已有索引）。
 * 由 app.ux 在收到 startFormula 传输完成时登记新条目、启动读回 index.json 时整体注入。
 * @param {object} obj key = "科目名#id"，value = {file, w, h}
 */
function setUserFormulaIndex(obj) {
  if (!obj || typeof obj !== 'object') return
  for (const key in obj) {
    const v = obj[key]
    if (!v || !v.file) continue
    userFormulaIndex[key] = {
      file: String(v.file),
      w: Number(v.w) || 0,
      h: Number(v.h) || 0
    }
  }
}

/**
 * 取用户公式图片索引当前快照（供 app.ux 落盘 index.json 全量重写）。
 * @returns {object}
 */
function getUserFormulaIndex() {
  return userFormulaIndex
}


/**
 * 删除一个用户导入科目(仅当 builtinNames 不包含该名时才删——保护内置同名的延伸条目，
 * 内置科目即使有同名用户延伸条目也不在此处理)。
 * 删除后由调用方(app.ux removeImportedSubject)负责落盘全量快照。
 * @param {string} name 科目名
 * @returns {boolean} 是否真删了(false=没这个用户科目 / 是内置科目名 / userSubjects 未加载)
 */
function removeSubject(name) {
  if (!name || !userSubjects) return false
  if (builtinNames.indexOf(name) !== -1) return false   // 内置科目名不删
  if (!userSubjects.hasOwnProperty(name)) return false
  delete userSubjects[name]
  // 同步清理该科目的公式图片索引条目（图片文件删除由 app.ux removeImportedSubject 负责）
  const prefix = name + '#'
  for (const key in userFormulaIndex) {
    if (key.indexOf(prefix) === 0) delete userFormulaIndex[key]
  }
  return true
}


/**
 * 科目列表：内置置顶(imported=false)，用户科目按导入顺序追加在底(imported=true)。
 * @returns {Array<{name:string,count:number,imported:boolean}>}
 */
function getSubjects() {
  const result = []
  // 内置
  for (let i = 0; i < builtinNames.length; i++) {
    const name = builtinNames[i]
    result.push({ name, count: (builtin[name] || []).length + userItemCount(name), imported: false })
  }
  // 用户新增科目（内置无的）
  if (userSubjects) {
    const userNames = Object.keys(userSubjects)
    for (let k = 0; k < userNames.length; k++) {
      const name = userNames[k]
      if (builtinNames.indexOf(name) === -1) {
        result.push({ name, count: userSubjects[name].length, imported: true })
      }
    }
  }
  return result
}

// 同名科目下，用户与内置不冲突的条目数（用于 getSubjects 的 count）
function userItemCount(name) {
  if (!userSubjects || !userSubjects[name]) return 0
  const builtinList = builtin[name] || []
  const builtinIds = {}
  for (let i = 0; i < builtinList.length; i++) builtinIds[builtinList[i].id] = true
  const userList = userSubjects[name]
  let n = 0
  for (let j = 0; j < userList.length; j++) {
    if (!builtinIds[userList[j].id]) n++
  }
  return n
}

/**
 * 取某科目条目数组：内置在前，用户同 id 跳过、不冲突的追加在后。
 * 每条若含 formulas 且有预渲染图（pointIndex 命中 subj#id），附加 formulaImg 供 content 页 <img> 直用。
 * @param {string} name
 * @returns {Array}
 */
function getKnowledge(name) {
  const list = (builtin[name] || []).slice()
  if (userSubjects && userSubjects[name]) {
    const builtinIds = {}
    for (let i = 0; i < list.length; i++) builtinIds[list[i].id] = true
    const userList = userSubjects[name]
    for (let j = 0; j < userList.length; j++) {
      if (!builtinIds[userList[j].id]) list.push(userList[j])
    }
  }
  attachFormulaImg(name, list)
  return list
}

/**
 * 给条目列表附加 formulaImg / formulaW / formulaH（per-point 一张预渲染 PNG，key 形如 "数学#1"，
 * _point_index 此 key 指向 {img,w,h})。content 正文图只用 formulaImg；弹层大图按 formulaW/formulaH
 * 算档位像素高 zoomH = formulaH × zoomW / formulaW。
 * 仅在条目含 formulas 且 pointIndex 命中时注入；查不到保持原样，content 页 if 判空不渲染公式区。
 */
function attachFormulaImg(subjectName, list) {
  if (!list || !list.length) return
  for (let i = 0; i < list.length; i++) {
    const it = list[i]
    if (!it || !Array.isArray(it.formulas) || !it.formulas.length) continue
    if (it.id === undefined || it.id === null) continue
    const pk = subjectName + '#' + it.id
    // 内置优先（随包资源）；内置查不到再看用户推送的公式图（internal://files/formulas/ 下）
    const entry = pointIndex && pointIndex[pk]
    if (entry && entry.img) {
      it.formulaImg = entry.img
      it.formulaW = entry.w || 0
      it.formulaH = entry.h || 0
    } else {
      const ue = userFormulaIndex[pk]
      if (ue && ue.file) {
        it.formulaImg = 'internal://files/formulas/' + ue.file
        it.formulaW = ue.w || 0
        it.formulaH = ue.h || 0
      }
    }
  }
}

/**
 * 全量扁平条目，供 search 页预构建 haystack。
 * @returns {Array<{title,desc,points,subjectName}>}
 */
function getAllItems() {
  const all = []
  const subjects = getSubjects()
  for (let s = 0; s < subjects.length; s++) {
    const name = subjects[s].name
    const items = getKnowledge(name)
    for (let i = 0; i < items.length; i++) {
      const it = items[i]
      all.push({
        title: it.title,
        desc: it.desc,
        points: it.points,
        raw: it.raw || '',
        formulas: it.formulas || [],
        subjectName: name
      })
    }
  }
  return all
}

export { setUserData, getSubjects, getKnowledge, getAllItems, loadExtraByName, mergeParsedInto, getUserDataJSON, removeSubject, setUserFormulaIndex, getUserFormulaIndex }
export default { setUserData, getSubjects, getKnowledge, getAllItems, loadExtraByName, mergeParsedInto, getUserDataJSON, removeSubject, setUserFormulaIndex, getUserFormulaIndex }
