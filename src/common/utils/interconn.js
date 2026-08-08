/**
 * interconn - 蓝牙通信连接管理器
 *
 * 彻底移除所有 ES2022+ 语法（class fields、static fields、object spread/rest），
 * 改用传统构造函数 + prototype 模式，保证小米手环 10 Pro 旧引擎兼容。
 * 使用延迟加载 @system.interconnect，避免 10 Pro 安装时 feature 不支持导致失败。
 */

var _interconnect = null;

function _getInterconnect() {
  if (_interconnect !== null) return _interconnect;
  try {
    _interconnect = require('@system.interconnect').default || require('@system.interconnect');
  } catch (e) {
    console.log('[interconn] require @system.interconnect FAIL: ' + e);
    _interconnect = false;
  }
  return _interconnect;
}

function interconn() {
  var self = this;
  this.callbacks = {};
  this.eventListeners = [];
  this.connected = false;

  var ic = _getInterconnect();
  if (!ic) {
    console.log('[interconn] @system.interconnect not available');
    return;
  }

  try {
    this.conn = ic.instance();
  } catch (e) {
    console.log('[interconn] interconnect.instance FAIL: ' + e);
    return;
  }

  this.conn.onmessage = function(evt) {
    var data = evt ? evt.data : undefined;
    console.log('[interconn] onmessage: type=' + typeof data + ', value=' + (typeof data === 'string' ? data.substring(0, 200) : JSON.stringify(data).substring(0, 200)));

    var parsed;
    if (typeof data === 'string') {
      try {
        parsed = JSON.parse(data);
      } catch (e) {
        console.error('[interconn] onmessage JSON.parse failed: ' + (e && e.message));
        return;
      }
    } else if (typeof data === 'object' && data !== null) {
      parsed = data;
    } else {
      console.warn('[interconn] onmessage received unexpected data type: ' + typeof data);
      return;
    }

    var tag = parsed.tag;
    // 手动拷贝 payload（不使用 ES2018 object rest spread）
    var playload = {};
    for (var k in parsed) {
      if (k !== 'tag' && parsed.hasOwnProperty(k)) {
        playload[k] = parsed[k];
      }
    }
    console.log('[interconn] message tag=' + tag + ', payload keys=' + Object.keys(playload).join(','));

    self.connected = true;
    if (self.callbacks[tag]) {
      try {
        self.callbacks[tag](playload);
      } catch (e) {
        console.error('[interconn] callback error for tag=' + tag + ': ' + (e && e.message));
      }
    } else {
      console.warn('[interconn] no handler for tag=' + tag + ', available tags=' + Object.keys(self.callbacks).join(','));
    }
  };

  this.conn.onclose = function() {
    console.log('[interconn] onclose');
    self.connected = false;
    for (var i = 0; i < self.eventListeners.length; i++) {
      var cb = self.eventListeners[i];
      if (cb) cb("close");
    }
  };

  this.conn.onerror = function(e) {
    console.error('[interconn] onerror: ' + (e && JSON.stringify(e)));
    self.connected = false;
    for (var i = 0; i < self.eventListeners.length; i++) {
      var cb = self.eventListeners[i];
      if (cb) cb("error");
    }
  };

  this.conn.onopen = function() {
    console.log('[interconn] onopen');
    self.connected = true;
    for (var i = 0; i < self.eventListeners.length; i++) {
      var cb = self.eventListeners[i];
      if (cb) cb("open");
    }
  };
}

interconn.prototype.addListener = function(tag, callback) {
  console.log('[interconn] addListener: tag=' + tag);
  this.callbacks[tag] = callback;
};

interconn.prototype.removeListener = function(tag) {
  console.log('[interconn] removeListener: tag=' + tag);
  delete this.callbacks[tag];
};

interconn.prototype.addEventListener = function(callback) {
  // 复用已置为 null 的槽位，避免数组索引错位
  for (var i = 0; i < this.eventListeners.length; i++) {
    if (this.eventListeners[i] === null) {
      this.eventListeners[i] = callback;
      return i;
    }
  }
  return this.eventListeners.push(callback) - 1;
};

interconn.prototype.removeEventListener = function(index) {
  if (index >= 0 && index < this.eventListeners.length) {
    this.eventListeners[index] = null;
  }
};

interconn.prototype.send = function(tag, playload) {
  var self = this;
  var data;
  if (typeof playload === 'object' && playload !== null) {
    // 手动拷贝（不使用 ES2018 object spread）
    data = {};
    for (var k in playload) {
      if (playload.hasOwnProperty(k)) data[k] = playload[k];
    }
    data.tag = tag;
  } else {
    data = { msg: playload, tag: tag };
  }
  console.log('[interconn] send: tag=' + tag + ', data=' + JSON.stringify(data).substring(0, 200));
  return new Promise(function(resolve, reject) {
    self.conn.send({
      data: data,
      success: function() {
        console.log('[interconn] send success: tag=' + tag);
        resolve();
      },
      fail: function(e) {
        console.error('[interconn] send fail: tag=' + tag + ', error=' + JSON.stringify(e));
        reject(e);
      }
    });
  });
};

interconn.prototype.register = function(module) {
  var self = this;
  if (typeof module !== 'function') throw new Error('module must be a function');
  if (!module.prototype || !module.prototype.__interconnModule__) throw new Error('module must be a interconnModule');
  var moduleName = module.prototype.name;
  console.log('[interconn] register module: name=' + moduleName);
  return new module({
    send: function(playload) { return self.send(moduleName, playload); },
    addListener: function(callback) { self.addListener(moduleName, callback); },
    conn: this.conn,
    removeListener: function() { self.removeListener(moduleName); },
    setEventListener: function(listener) { self.addEventListener(listener); }
  });
};

// 旧版 get state() 改为普通方法（getter 在部分旧引擎上可能行为不一致）
interconn.prototype.getState = function() {
  return this.conn.getApkStatus();
};

// interconnModule: 使用构造函数 + prototype 替代 class + static field
function interconnModule() {}
interconnModule.prototype.__interconnModule__ = true;

export default interconn;
export { interconnModule };
