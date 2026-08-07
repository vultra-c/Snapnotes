/**
 * 考点阅读器 - 文件传输模块
 *
 * 完全移除 ES2022+ 语法（class fields、static fields、async/await、object spread、Map），
 * 改用传统构造函数 + prototype 模式 + Promise.then() 链，保证小米手环 10 Pro 旧引擎兼容。
 *
 * 消息协议（tag="file"）：
 * 接收方向（stat 字段路由）：
 *   - startTransfer: 开始传输 { filename, total, wordCount, startFrom }
 *   - d: 数据分片 { count, data }
 *   - chapter_complete: 章节完成 { count }
 *   - transfer_complete: 传输完成
 *   - cancel: 取消传输
 *
 * 发送方向（type 字段）：
 *   - ready: 手环就绪 { count, usage }
 *   - chapter_chunk_complete: 章节分片完成
 *   - next_chunk: 请求下一片
 *   - chapter_saved: 章节已保存 { count, syncedCount, totalCount, progress }
 *   - transfer_finished: 传输完成
 *   - error: 错误 { message, count }
 *   - cancel: 取消
 */
import dataManager from './dataManager.js';
import file from '@system.file';
import storage from '@system.storage';
import { base64ToArrayBuffer } from './base64.js';

function interconnfile(options) {
  var opts = options || {};
  var self = this;

  this.send = opts.send;

  // 实例字段（原 class fields 移入构造函数）
  this.currentBookName = "";
  this.totalChapters = 0;
  this.receivedChapters = 0;
  this.currentChapterFileId = null;
  this.currentChapterIndex = 0;
  this.targetFolder = 'bt_root';

  // 用普通对象替代 ES6 Map
  this.chapterWriteState = {};
  this._lastProgressTime = 0;

  // 公式图片传输状态
  this.formulaTransferring = false;
  this.formulaFilename = '';
  this.formulaSubject = '';
  this.formulaId = 0;
  this.formulaWidth = 0;
  this.formulaHeight = 0;
  this.formulaTotalChunks = 0;
  this.formulaReceivedChunks = 0;
  this.formulaBase64Data = '';

  // 默认回调（空函数）
  this._callback = function() {};

  var onmessage = function(data) {
    var stat = data.stat;
    // 手动拷贝 payload（不使用 ES2018 object rest spread）
    var payload = {};
    for (var k in data) {
      if (k !== 'stat' && data.hasOwnProperty(k)) {
        payload[k] = data[k];
      }
    }
    try {
      switch (stat) {
        case "startTransfer":
          self.startTransfer(payload);
          break;
        case "d":
          if (self.formulaTransferring) {
            self.saveFormulaChunk(payload);
          } else {
            self.saveChapter(payload);
          }
          break;
        case "chapter_complete":
          self.completeChapterTransfer(payload);
          break;
        case "transfer_complete":
          self.handleTransferComplete();
          break;
        case "startFormula":
          self.startFormulaTransfer(payload);
          break;
        case "transferComplete":
          self.completeFormulaTransfer(payload);
          break;
        case "cancel":
          self.handleCancel();
          break;
        default:
          console.warn('[BT-File] Unknown stat: ' + stat);
      }
    } catch (e) {
      self.handleError(e, "Message processing error");
    }
  };

  if (opts.addListener) opts.addListener(onmessage);

  if (opts.setEventListener) {
    opts.setEventListener(function(event) {
      if (event !== 'open') {
        self.resetState();
        self._callback({ msg: "error", error: event, filename: self.currentBookName });
      }
    });
  }
}

// 标记为 interconnModule（替代 static field）
interconnfile.prototype.__interconnModule__ = true;
interconnfile.prototype.name = 'file';

interconnfile.prototype.resetState = function() {
  this.currentBookName = "";
  this.currentChapterFileId = null;
  this.currentChapterIndex = 0;
  this.receivedChapters = 0;
  this.totalChapters = 0;
  this.targetFolder = 'bt_root';
  this.chapterWriteState = {};
  this._lastProgressTime = 0;
  this.formulaTransferring = false;
  this.formulaFilename = '';
  this.formulaSubject = '';
  this.formulaId = 0;
  this.formulaWidth = 0;
  this.formulaHeight = 0;
  this.formulaTotalChunks = 0;
  this.formulaReceivedChunks = 0;
  this.formulaBase64Data = '';
};

interconnfile.prototype._emitProgress = function(progress, force) {
  var now = Date.now();
  if (force || progress >= 1 || (now - this._lastProgressTime) >= 200) {
    this._lastProgressTime = now;
    this._callback({ msg: "next", progress: progress, filename: this.currentBookName });
  }
};

interconnfile.prototype.startTransfer = function(payload) {
  var self = this;
  var filename = payload.filename;
  var total = payload.total;
  var wordCount = payload.wordCount;
  var startFrom = payload.startFrom || 0;
  var folder = payload.folder;

  console.log('[BT-File] startTransfer: ' + filename + ', total=' + total + ', startFrom=' + startFrom + ', folder=' + (folder || 'bt_root'));

  this.currentBookName = filename;
  this.totalChapters = total;
  this.receivedChapters = startFrom;
  this.currentChapterFileId = null;
  this.currentChapterIndex = 0;
  this.chapterWriteState = {};
  this.targetFolder = (folder && folder !== '') ? folder : 'bt_root';
  console.log('[BT-File] targetFolder set to: ' + this.targetFolder);

  this._callback({ msg: "start", total: total, filename: filename });

  this.send({ type: "ready", count: startFrom, usage: 0 }).then(function() {
    console.log('[BT-File] ready sent');
  }, function(e) {
    console.error('[BT-File] ready send fail: ' + e);
  });
};

interconnfile.prototype.saveChapter = function(payload) {
  var self = this;
  var count = payload.count;
  var dataStr = payload.data;

  var chapterData;
  try {
    chapterData = JSON.parse(dataStr);
  } catch (e) {
    console.error('[BT-File] JSON.parse chapter data fail: ' + e);
    return;
  }
  var index = chapterData.index;
  var name = chapterData.name;
  var content = chapterData.content;
  var chunkNum = chapterData.chunkNum;
  var totalChunks = chapterData.totalChunks;

  console.log('[BT-File] Chunk ' + (chunkNum + 1) + '/' + totalChunks + ' for chapter ' + index);

  var state = this.chapterWriteState[index] || {
    started: false,
    completed: false,
    lastChunkNum: -1,
    totalChunks: 0,
    fileId: null
  };

  if (state.completed && chunkNum !== 0) {
    var overallProgress = (count + ((chunkNum + 1) / totalChunks)) / this.totalChapters;
    this._emitProgress(overallProgress, false);

    if (chunkNum === totalChunks - 1) {
      this.send({ type: "chapter_chunk_complete" });
    } else {
      this.send({ type: "next_chunk" });
    }
    return;
  }

  var isFirstChunk = chunkNum === 0;
  var isLastChunk = chunkNum === totalChunks - 1;

  if (isFirstChunk) {
    state.started = true;
    state.completed = false;
    state.lastChunkNum = -1;
    state.totalChunks = totalChunks;

    var displayName = this.totalChapters > 1
      ? this.currentBookName + ' - 第' + (index + 1) + '章'
      : this.currentBookName;

    state.fileId = dataManager.startBluetoothTransferSession(displayName, this.targetFolder);
    console.log('[BT-File] Transfer session started: ' + state.fileId + ' for "' + displayName + '"');
  }

  dataManager.writeBluetoothChunk(state.fileId, content || '', isLastChunk).then(function(ok) {
    if (!ok) {
      dataManager.cleanupBluetoothTransfer(state.fileId).then(function() {
        delete self.chapterWriteState[index];
        var errMsg = '存储写入失败（chunk ' + (chunkNum + 1) + '/' + totalChunks + '）';
        console.error('[BT-File] ' + errMsg);
        self.send({ type: "error", message: errMsg, count: 0 });
      });
      return;
    }

    state.lastChunkNum = chunkNum;
    self.chapterWriteState[index] = state;

    var overallProgress = (count + ((chunkNum + 1) / totalChunks)) / self.totalChapters;
    self._emitProgress(overallProgress, isLastChunk);

    if (isLastChunk) {
      state.completed = true;
      self.chapterWriteState[index] = state;
      self.currentChapterFileId = state.fileId;
      self.send({ type: "chapter_chunk_complete" });
    } else {
      self.send({ type: "next_chunk" });
    }
  }, function(e) {
    console.error('[BT-File] writeBluetoothChunk error: ' + e);
    self.send({ type: "error", message: '写入失败: ' + e, count: 0 });
  });
};

interconnfile.prototype.completeChapterTransfer = function(payload) {
  var self = this;
  var count = payload.count;
  console.log('[BT-File] Chapter complete: ' + count);

  var finishSave = function() {
    self.chapterWriteState = {};
    self.send({
      type: "chapter_saved",
      count: self.receivedChapters,
      syncedCount: self.receivedChapters,
      totalCount: self.totalChapters,
      progress: (self.receivedChapters / self.totalChapters) * 100
    });
  };

  if (this.currentChapterFileId) {
    console.log('[BT-File] Finalizing transfer: ' + this.currentChapterFileId);
    dataManager.finalizeBluetoothTransfer(this.currentChapterFileId).then(function(savedId) {
      if (!savedId) {
        self.send({ type: "error", message: '存储写入失败（可能存储空间不足）', count: 0 });
        return;
      }
      self.receivedChapters++;
      self.currentChapterFileId = null;
      finishSave();
    }, function(e) {
      var errMsg = (e && e.message) ? e.message : String(e || '未知错误');
      console.error('[BT-File] Save chapter failed: ' + errMsg);
      self.send({ type: "error", message: '保存失败: ' + errMsg, count: 0 });
    });
  } else {
    finishSave();
  }
};

interconnfile.prototype.handleTransferComplete = function() {
  var self = this;
  console.log('[BT-File] Transfer complete');
  this.resetState();
  this.send({ type: "transfer_finished" }).then(function() {
    self._callback({ msg: "success" });
  }, function() {
    self._callback({ msg: "success" });
  });
};

interconnfile.prototype.handleCancel = function() {
  var self = this;
  console.log('[BT-File] Transfer cancelled');
  this.resetState();
  this.send({ type: "cancel" }).then(function() {
    self._callback({ msg: "cancel" });
  }, function() {
    self._callback({ msg: "cancel" });
  });
};

// ==================== 公式图片传输 ====================

interconnfile.prototype.startFormulaTransfer = function(payload) {
  var self = this;
  var subject = payload.subject;
  var id = payload.id;
  var filename = payload.filename;
  var w = payload.w;
  var h = payload.h;
  var totalChunks = payload.totalChunks;
  var totalBytes = payload.totalBytes;

  console.log('[BT-Formula] startFormula: subject=' + subject + ', id=' + id
    + ', file=' + filename + ', ' + w + 'x' + h
    + ', chunks=' + totalChunks + ', bytes=' + totalBytes);

  this.formulaTransferring = true;
  this.formulaSubject = subject || '';
  this.formulaId = id || 0;
  this.formulaFilename = filename || 'formula.png';
  this.formulaWidth = w || 336;
  this.formulaHeight = h || 100;
  this.formulaTotalChunks = totalChunks || 0;
  this.formulaReceivedChunks = 0;
  this.formulaBase64Data = '';

  this._callback({
    msg: "formula_start",
    filename: this.formulaFilename,
    subject: this.formulaSubject,
    id: this.formulaId
  });

  this.send({ type: "ready", nextChunkIndex: 0 }).then(function() {
    console.log('[BT-Formula] ready sent');
  }, function(e) {
    console.error('[BT-Formula] ready send fail: ' + e);
  });
};

interconnfile.prototype.saveFormulaChunk = function(payload) {
  var self = this;
  var chunkIndex = payload.chunkIndex;
  var totalChunks = payload.totalChunks;
  var data = payload.data;

  console.log('[BT-Formula] chunk ' + (chunkIndex + 1) + '/' + totalChunks
    + ' size=' + (data ? data.length : 0));

  this.formulaBase64Data += (data || '');
  this.formulaReceivedChunks = chunkIndex + 1;

  var isLast = (chunkIndex >= totalChunks - 1);

  if (isLast) {
    this.send({ type: "chapter_chunk_complete" });
  } else {
    this.send({ type: "next_chunk" });
  }
};

interconnfile.prototype.completeFormulaTransfer = function() {
  var self = this;
  console.log('[BT-Formula] transferComplete: decoding base64 and writing file...');

  if (!this.formulaBase64Data || this.formulaBase64Data.length === 0) {
    console.error('[BT-Formula] No base64 data received');
    this.send({ type: "error", message: "公式图片数据为空", count: 0 });
    this.formulaTransferring = false;
    return;
  }

  try {
    var arrayBuffer = base64ToArrayBuffer(this.formulaBase64Data);
    if (!arrayBuffer || arrayBuffer.byteLength === 0) {
      throw new Error('base64 解码结果为空');
    }
    console.log('[BT-Formula] Decoded PNG: ' + arrayBuffer.byteLength + ' bytes');

    var fileUri = 'internal://files/' + this.formulaFilename;

    // 写入文件系统（Promise.then 链替代 async/await）
    new Promise(function(resolve, reject) {
      file.writeListFile({
        uri: fileUri,
        data: arrayBuffer,
        success: function() {
          console.log('[BT-Formula] File written: ' + fileUri);
          resolve();
        },
        fail: function(err) {
          console.error('[BT-Formula] writeListFile fail: ' + JSON.stringify(err));
          reject(new Error('文件写入失败: ' + JSON.stringify(err)));
        }
      });
    }).then(function() {
      // 记录公式图片元信息到 storage
      return new Promise(function(resolve) {
        storage.set({
          key: 'KD_FORMULA_' + self.formulaFilename,
          value: JSON.stringify({
            subject: self.formulaSubject,
            id: self.formulaId,
            w: self.formulaWidth,
            h: self.formulaHeight,
            uri: fileUri
          }),
          success: function() { resolve(true); },
          fail: function() { resolve(false); }
        });
      });
    }).then(function() {
      console.log('[BT-Formula] Formula image saved: ' + fileUri);
      self._callback({
        msg: "formula_saved",
        filename: self.formulaFilename,
        subject: self.formulaSubject,
        id: self.formulaId,
        uri: fileUri
      });
      self.send({ type: "transfer_finished" });
    }, function(e) {
      var errMsg = (e && e.message) ? e.message : String(e || '未知错误');
      console.error('[BT-Formula] Save formula failed: ' + errMsg);
      self.send({ type: "error", message: '公式图片保存失败: ' + errMsg, count: 0 });
    }).then(function() {
      // finally：清理公式传输状态
      self.formulaTransferring = false;
      self.formulaBase64Data = '';
    });
  } catch (e) {
    var errMsg = (e && e.message) ? e.message : String(e || '未知错误');
    console.error('[BT-Formula] Save formula failed: ' + errMsg);
    this.send({ type: "error", message: '公式图片保存失败: ' + errMsg, count: 0 });
    this.formulaTransferring = false;
    this.formulaBase64Data = '';
  }
};

interconnfile.prototype.handleError = function(error, context) {
  var errorMsg = (error && error.message) ? error.message : String(error || '未知错误');
  var displayMsg = context + ': ' + errorMsg;
  console.error('[BT-File] ' + displayMsg);
  this.send({ type: "error", message: displayMsg, count: 0 });
  this._callback({ msg: "error", error: displayMsg });
};

interconnfile.prototype.setCallback = function(callback) {
  if (typeof callback === 'function') {
    this._callback = callback;
  }
};

export default interconnfile;
