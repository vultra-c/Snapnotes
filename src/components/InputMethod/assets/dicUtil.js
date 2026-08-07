import { getDict } from './dic.js'
import { getWords } from './dic_words.js'
import { getInitialsIndex } from './dic_words_initials.js'
import { syllables } from './pinyin_syllables.js'

// 辅助：从词库取值（支持单值和数组），去重推入 wordHits
function pushWordHits(val, arr) {
  if (!val) return
  if (Array.isArray(val)) {
    for (var i = 0; i < val.length; i++) {
      if (val[i] && arr.indexOf(val[i]) === -1) arr.push(val[i])
    }
  } else if (arr.indexOf(val) === -1) {
    arr.push(val)
  }
}

var SimpleInputMethod = {
  dict: {}
}

// 用普通对象替代 ES6 Set，保证旧引擎兼容（小米手环10 Pro 等）
function makeSyllableSet(arr) {
  var obj = {}
  for (var i = 0; i < arr.length; i++) {
    obj[arr[i]] = true
  }
  return obj
}

SimpleInputMethod.initDict = function() {
  // 幂等：已初始化则跳过
  if (this.dict.syllableSet) return
  try {
    this.dict.py2hz = getDict()
    this.dict.py2hz2 = {}
    this.dict.py2hz2['i'] = 'i'

    // 合法音节集合（用普通对象替代 ES6 Set）
    this.dict.syllableSet = makeSyllableSet(syllables)
    for (var key in this.dict.py2hz) {
      var ch = key[0]
      if (!this.dict.py2hz2[ch]) this.dict.py2hz2[ch] = this.dict.py2hz[key]
      this.dict.syllableSet[key] = true
    }

    // 整词词库
    this.dict.words = getWords()

    // 简拼索引
    this.dict.initialsIndex = getInitialsIndex()

    // forwardIndex 分片构建
    this._buildForwardIndex()
  } catch (e) {
    console.log('initDict error: ' + e)
  }
}

SimpleInputMethod._buildForwardIndex = function() {
  var wmap = this.dict.words || {}
  var fwd = this.dict.forwardIndex || (this.dict.forwardIndex = {})
  var keys = Object.keys(wmap)
  var CHUNK = 200
  var i = 0
  var self = this
  var step = function() {
    var end = Math.min(i + CHUNK, keys.length)
    for (; i < end; i++) {
      var key = keys[i]
      if (key.length >= 2) {
        var pref = key.charAt(0) + key.charAt(1)
        var fi = fwd[pref]
        if (fi) fi.push(key)
        else fwd[pref] = [key]
      }
    }
    if (i < keys.length) setTimeout(step, 0)
  }
  step()
}

SimpleInputMethod.getSingleHanzi = function(pinyin, lang) {
  lang = lang || 'cn'
  if (lang === 'cn') {
    return this.dict.py2hz2[pinyin] || this.dict.py2hz[pinyin] || ''
  }
  return ''
}

function getSylTopChar(dict, syl) {
  var c = dict.py2hz[syl] || ''
  if (c) return c[0]
  var w = dict.words && dict.words[syl]
  if (w) {
    var f = Array.isArray(w) ? w[0] : w
    return f ? f[0] : ''
  }
  return ''
}

SimpleInputMethod.segmentPinyin = function(pinyin) {
  if (!pinyin) return null
  var set = this.dict.syllableSet
  var result = []
  var pos = []
  var i = 0
  var maxLen = 24
  while (i < pinyin.length && i < maxLen) {
    var matched = ''
    for (var len = Math.min(6, pinyin.length - i); len >= 1; len--) {
      var s = pinyin.substr(i, len)
      if (set[s]) { matched = s; break }
    }
    if (!matched) break
    result.push(matched)
    i += matched.length
    pos.push(i)
  }
  var rest = pinyin.substr(i)
  var DUMMY_ENDING = { m: 1, n: 1, ng: 1, hm: 1, hng: 1 }
  if (!rest && result.length >= 2) {
    var lastSeg = result[result.length - 1]
    if (DUMMY_ENDING[lastSeg]) {
      rest = result.pop()
      pos.pop()
    }
  }
  if (!rest && result.length === 1) return null
  if (result.length === 0) return null
  return { segs: result, rest: rest, pos: pos }
}

SimpleInputMethod.completeSyllable = function(prefix, prevSyl) {
  if (!prefix) return ''
  var COMMON = {
    'z': 'zai', 'm': 'ma', 'b': 'bu', 'd': 'de', 'g': 'ge',
    'h': 'he', 'j': 'ji', 'k': 'ke', 'l': 'le', 'n': 'ne',
    'q': 'qi', 'r': 'ren', 's': 'shi', 't': 'ta', 'w': 'wo',
    'x': 'xi', 'y': 'yi',
    'zh': 'zhe', 'ch': 'chi', 'sh': 'shi',
    'p': 'ping', 'f': 'fa', 'c': 'ci', 'a': 'ai', 'o': 'ou',
  }
  var set = this.dict.syllableSet
  var wmap = this.dict.words || {}
  var candidates = []
  // 遍历音节集合（普通对象，非 ES6 Set）
  for (var syl in set) {
    if (set.hasOwnProperty(syl) && syl.indexOf(prefix) === 0 && syl.length > prefix.length) {
      candidates.push(syl)
    }
  }
  if (candidates.length === 0) return ''
  if (prevSyl) {
    for (var ci = 0; ci < candidates.length; ci++) {
      if (wmap[prevSyl + candidates[ci]]) return candidates[ci]
    }
  }
  if (COMMON[prefix]) return COMMON[prefix]
  return candidates[0]
}

SimpleInputMethod.tryStitchTrailing = function(segResult) {
  if (!segResult || !segResult.rest) return segResult
  var segs = segResult.segs
  var rest = segResult.rest
  var pos = segResult.pos
  if (!rest) return segResult
  var last = segs[segs.length - 1]
  if (last.length > 2) return segResult
  var combined = last + rest
  if (this.dict.syllableSet[combined]) {
    var newSegs = segs.slice(0, -1).concat([combined])
    var newPos = pos.slice()
    newPos[newPos.length - 1] = (newPos[newPos.length - 1] || 0) + rest.length
    return { segs: newSegs, rest: '', pos: newPos }
  }
  if (last.length <= 2) {
    var cleanSegs = segs.slice(0, -1)
    var cleanPos = pos.slice(0, -1)
    if (cleanSegs.length >= 2) {
      return { segs: cleanSegs, rest: '', pos: cleanPos }
    }
  }
  return segResult
}

SimpleInputMethod.matchMixedWords = function(pinyin) {
  if (!pinyin) return null
  var set = this.dict.syllableSet
  var tokens = []
  var offsets = []
  var i = 0
  while (i < pinyin.length) {
    var matched = ''
    for (var len = Math.min(6, pinyin.length - i); len >= 1; len--) {
      var s = pinyin.substr(i, len)
      if (set[s]) { matched = s; break }
    }
    if (matched) { tokens.push(matched); i += matched.length }
    else { tokens.push(pinyin[i]); i += 1 }
    offsets.push(i)
  }
  if (tokens.length < 2) return null

  var mixedAbbr = ''
  for (var ti = 0; ti < tokens.length; ti++) mixedAbbr += tokens[ti][0]

  var idx = this.dict.initialsIndex || {}
  var matchedKeys = idx[mixedAbbr] || []
  if (matchedKeys.length === 0) return null

  var hits = []
  for (var ki = 0; ki < matchedKeys.length && hits.length < 8; ki++) {
    var key = matchedKeys[ki]
    var keySegs = this.segmentPinyin(key)
    if (!keySegs || !keySegs.segs || keySegs.segs.length !== tokens.length) continue
    var syls = keySegs.segs
    var ok = true
    for (var j = 0; j < tokens.length; j++) {
      var t = tokens[j]
      var syl = syls[j]
      if (t.length > 1) {
        if (t !== syl) { ok = false; break }
      } else if (t !== syl[0]) {
        ok = false; break
      }
    }
    if (ok) hits.push({ key: key, syls: syls, offsets: offsets })
  }
  return hits.length > 0 ? hits : null
}

SimpleInputMethod.getSegmentedDisplay = function(pinyin) {
  if (!pinyin) return ''
  if (!this.dict.syllableSet) return pinyin
  var len = pinyin.length
  if (len === 1 || this.dict.syllableSet[pinyin]) return pinyin

  var set = this.dict.syllableSet
  var tokens = []
  var ii = 0
  while (ii < len) {
    var mtch = ''
    for (var l = Math.min(6, len - ii); l >= 1; l--) {
      if (set[pinyin.substr(ii, l)]) { mtch = pinyin.substr(ii, l); break }
    }
    if (mtch) { tokens.push(mtch); ii += mtch.length }
    else { tokens.push(pinyin[ii]); ii += 1 }
  }
  if (tokens.length >= 2) return tokens.join("'")
  return pinyin
}

SimpleInputMethod.getMultiHanzi = function(pinyin, lang) {
  lang = lang || 'cn'
  var empty = { words: [], composed: '', segs: null, sylTopChars: [] }
  if (lang !== 'cn') return empty
  if (!this.dict.syllableSet || !this.dict.words) return empty

  var wmap = this.dict.words || {}
  var wordHits = []
  var matchSource = ''

  // 1) 词库整串精确命中
  if (wmap[pinyin]) { pushWordHits(wmap[pinyin], wordHits); matchSource = 'exact' }
  // 前缀命中
  if (wordHits.length === 0) {
    var max = Math.min(pinyin.length, 12)
    for (var len = max; len >= 2; len--) {
      var head = pinyin.substr(0, len)
      if (wmap[head]) { pushWordHits(wmap[head], wordHits); matchSource = 'prefix'; break }
    }
  }
  // 前向前缀匹配
  if (wordHits.length === 0 && pinyin.length >= 2 && !this.dict.syllableSet[pinyin]) {
    var pref = pinyin.substr(0, 2)
    var fwdIdx = this.dict.forwardIndex || {}
    var candidates = fwdIdx[pref] || []
    var fwdCount = 0
    for (var ki2 = 0; ki2 < candidates.length && fwdCount < 6; ki2++) {
      if (candidates[ki2].indexOf(pinyin) === 0) {
        pushWordHits(wmap[candidates[ki2]], wordHits); matchSource = 'forward'
        fwdCount++
      }
    }
  }
  // 首字母简拼匹配
  if (wordHits.length === 0 && pinyin.length >= 2 && pinyin.length <= 8 && !this.dict.syllableSet[pinyin]) {
    var idx2 = this.dict.initialsIndex || {}
    var keys2 = idx2[pinyin] || []
    for (var k = 0; k < keys2.length && k < 6; k++) {
      pushWordHits(wmap[keys2[k]], wordHits); matchSource = 'initials'
    }
  }

  // 混合匹配
  var mixedInfo = null
  if (wordHits.length === 0 && pinyin.length >= 2 && pinyin.length <= 8 && !this.dict.syllableSet[pinyin]) {
    var mixed = this.matchMixedWords(pinyin)
    if (mixed && mixed.length > 0) {
      for (var mi = 0; mi < mixed.length; mi++) {
        pushWordHits(wmap[mixed[mi].key], wordHits)
      }
      matchSource = 'mixed'
      mixedInfo = mixed[0]
    }
  }

  // 分词逐字组合
  var rawSeg = this.segmentPinyin(pinyin)
  var segResult = rawSeg ? this.tryStitchTrailing(rawSeg) : null
  var segs = segResult ? segResult.segs : null
  var pos = segResult ? segResult.pos : null
  var rest = segResult ? segResult.rest : ''

  if (segs) {
    var DUMMY = { m: 1, n: 1, ng: 1, hm: 1, hng: 1 }
    var keep = []
    var keepPos = []
    for (var j2 = 0; j2 < segs.length; j2++) {
      if (DUMMY[segs[j2]]) {
        if ((this.dict.py2hz[segs[j2]] || '')[0]) { keep.push(segs[j2]); keepPos.push(pos[j2]) }
      } else {
        keep.push(segs[j2]); keepPos.push(pos[j2])
      }
    }
    segs = keep.length > 0 ? keep : segs
    pos = keep.length > 0 ? keepPos : pos
  }

  var sylTopChars = []
  if (segs) {
    for (var j3 = 0; j3 < segs.length; j3++) {
      var ch2 = getSylTopChar(this.dict, segs[j3])
      if (ch2) {
        sylTopChars.push({ char: ch2, offset: pos[j3] })
      }
    }
  }

  if (segs && rest) {
    var prevSyl = segs.length >= 1 ? segs[segs.length - 1] : ''
    var completed = this.completeSyllable(rest, prevSyl)
    if (completed) {
      segs = segs.concat([completed])
      var rawOffset = (pos.length > 0 ? pos[pos.length - 1] : 0) + completed.length
      var lastOffset = Math.min(rawOffset, pinyin.length)
      var ch3 = getSylTopChar(this.dict, completed)
      if (ch3) sylTopChars.push({ char: ch3, offset: lastOffset })
    }
  }

  var composed = ''
  if (segs) {
    var wmap2 = this.dict.words || {}
    var result = []
    var i2 = 0
    while (i2 < segs.length) {
      var matched2 = false
      for (var len2 = Math.min(4, segs.length - i2); len2 >= 2; len2--) {
        var key2 = segs.slice(i2, i2 + len2).join('')
        var hit = wmap2[key2]
        if (hit) {
          result.push(Array.isArray(hit) ? hit[0] : hit)
          i2 += len2
          matched2 = true
          break
        }
      }
      if (!matched2) {
        var ch4 = getSylTopChar(this.dict, segs[i2])
        result.push(ch4)
        i2++
      }
    }
    composed = result.join('')
    if (composed.length !== segs.length) composed = ''
  }

  var wout = []
  for (var wi = 0; wi < wordHits.length; wi++) {
    if (wordHits[wi] && wout.indexOf(wordHits[wi]) === -1) wout.push(wordHits[wi])
  }

  if (matchSource === 'initials' && pinyin.length >= 2) {
    var abbrChars = []
    for (var wi2 = 0; wi2 < wout.length && abbrChars.length < 12; wi2++) {
      var word = wout[wi2]
      for (var ci2 = 0; ci2 < word.length && abbrChars.length < 12; ci2++) {
        var ch5 = word[ci2]
        var offset = ci2 + 1
        var found = false
        for (var ai = 0; ai < abbrChars.length; ai++) {
          if (abbrChars[ai].char === ch5 && abbrChars[ai].offset === offset) { found = true; break }
        }
        if (!found) abbrChars.push({ char: ch5, offset: offset })
      }
    }
    if (abbrChars.length > 0) sylTopChars = abbrChars
  } else if (!segs && wout.length > 0) {
    var fullChars = []
    for (var fi = 0; fi < wout.length && fullChars.length < 8; fi++) {
      var fch = wout[fi].charAt(0) || ''
      var fdup = false
      for (var di = 0; di < fullChars.length; di++) {
        if (fullChars[di].char === fch) { fdup = true; break }
      }
      if (fch && !fdup) fullChars.push({ char: fch, offset: pinyin.length })
    }
    if (fullChars.length > 0) sylTopChars = fullChars
  }

  if (matchSource === 'mixed' && mixedInfo && mixedInfo.syls) {
    var miSyls = mixedInfo.syls
    var mchars = []
    for (var mj = 0; mj < miSyls.length; mj++) {
      var mch = getSylTopChar(this.dict, miSyls[mj])
      if (mch) mchars.push({ char: mch, offset: mixedInfo.offsets[mj] })
    }
    if (mchars.length > 0) sylTopChars = mchars
  }

  return { words: wout, composed: composed, segs: segs, sylTopChars: sylTopChars }
}

SimpleInputMethod.getHanzi = function(pinyin, lang) {
  lang = lang || 'cn'
  // 未初始化守卫
  if (!this.dict.syllableSet) return { chars: [], matched: '', multi: null }
  var chars = []
  var matched = ''
  var result = this.getSingleHanzi(pinyin, lang)
  if (result) {
    chars = result.split('')
    matched = pinyin
  } else {
    var max = Math.min(pinyin.length, 6)
    for (var len = max; len >= 1; len--) {
      var head = pinyin.substr(0, len)
      var rs = this.getSingleHanzi(head, lang)
      if (rs) {
        chars = rs.split('')
        matched = head
        break
      }
    }
  }

  var multi = null
  if (lang === 'cn') {
    multi = this.getMultiHanzi(pinyin, lang)
  }

  return { chars: chars, matched: matched, multi: multi }
}

export { SimpleInputMethod }
