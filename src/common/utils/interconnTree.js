/**
 * 考点阅读器 - 文件树同步模块
 *
 * 作为 interconnModule 注册到连接管理器，tag="tree"
 * 负责文件树的同步、文件夹创建/删除等操作
 *
 * 消息协议（tag="tree"）：
 * 接收方向（action 字段路由）：
 *   - getTree: 请求文件树 { }
 *   - createFolder: 创建文件夹 { name, parentId }
 *   - deleteNode: 删除节点 { nodeId }
 *   - renameNode: 重命名节点 { nodeId, newName }
 *
 * 发送方向（response 字段）：
 *   - treeData: 文件树数据 { tree: [...] }
 *   - folderCreated: 文件夹创建结果 { folderId, success, error }
 *   - nodeDeleted: 节点删除结果 { success, error }
 *   - nodeRenamed: 节点重命名结果 { success, error }
 *
 * 注：createFolder / deleteNode / renameNode 后不再自动全量推树，
 *     手机端收到成功响应后自行调用 requestTree 刷新。
 */
import { interconnModule } from './interconn.js';
import dataManager from './dataManager.js';

// 不使用 ES2022 static class fields，改为构造函数挂载（兼容小米手环10 Pro）
function interconnTree(options) {
  var opts = options || {};
  var self = this;

  this.send = opts.send;

  var onmessage = function(data) {
    var action = data.action;
    // 浅拷贝 payload（去掉 action 字段）
    var payload = {};
    for (var k in data) {
      if (k !== 'action' && data.hasOwnProperty(k)) payload[k] = data[k];
    }
    try {
      switch (action) {
        case 'getTree':
          self.handleGetTree();
          break;
        case 'createFolder':
          self.handleCreateFolder(payload);
          break;
        case 'deleteNode':
          self.handleDeleteNode(payload);
          break;
        case 'renameNode':
          self.handleRenameNode(payload);
          break;
        default:
          console.warn('[BT-Tree] Unknown action: ' + action);
      }
    } catch (e) {
      console.error('[BT-Tree] Error: ' + ((e && e.message) ? e.message : String(e || '未知错误')));
    }
  };

  if (opts.addListener) opts.addListener(onmessage);

  // When connection opens or reconnects, auto-send tree
  if (opts.setEventListener) {
    opts.setEventListener(function(event) {
      if (event === 'open') {
        setTimeout(function() { self.handleGetTree(); }, 500);
      }
    });
  }
}

// 标记为 interconnModule（替代 static field）
interconnTree.prototype.__interconnModule__ = true;
interconnTree.prototype.name = 'tree';

/**
 * 处理获取文件树请求
 */
interconnTree.prototype.handleGetTree = function() {
  var self = this;
  console.log('[BT-Tree] getTree request');
  dataManager.getFolderTreeForBluetooth().then(function(tree) {
    self.send({
      response: 'treeData',
      tree: tree
    }).then(function() {
      console.log('[BT-Tree] Tree sent (' + (tree ? tree.length : 0) + ' top-level nodes)');
    }, function() {
      console.log('[BT-Tree] send treeData failed');
    });
  }, function(e) {
    console.error('[BT-Tree] getTree error: ' + e);
  });
};

/**
 * 处理创建文件夹请求
 */
interconnTree.prototype.handleCreateFolder = function(payload) {
  var self = this;
  var name = payload.name;
  var parentId = payload.parentId;
  console.log('[BT-Tree] createFolder: ' + name + ' parentId=' + (parentId || 'bt_root'));
  dataManager.createBluetoothFolder(name, parentId).then(function(folderId) {
    self.send({
      response: 'folderCreated',
      folderId: folderId,
      success: true
    });
  }, function(e) {
    self.send({
      response: 'folderCreated',
      folderId: null,
      success: false,
      error: (e && e.message) ? e.message : String(e || '未知错误')
    });
  });
};

/**
 * 处理删除节点请求
 */
interconnTree.prototype.handleDeleteNode = function(payload) {
  var self = this;
  var nodeId = payload.nodeId;
  console.log('[BT-Tree] deleteNode: ' + nodeId);
  dataManager.deleteBluetoothNode(nodeId).then(function() {
    self.send({
      response: 'nodeDeleted',
      success: true
    });
  }, function(e) {
    self.send({
      response: 'nodeDeleted',
      success: false,
      error: (e && e.message) ? e.message : String(e || '未知错误')
    });
  });
};

/**
 * 处理重命名节点请求
 * @param {string} nodeId 节点 ID
 * @param {string} newName 新名称
 */
interconnTree.prototype.handleRenameNode = function(payload) {
  var self = this;
  var nodeId = payload.nodeId;
  var newName = payload.newName;
  console.log('[BT-Tree] renameNode: ' + nodeId + ' -> ' + newName);
  dataManager.renameBluetoothNode(nodeId, newName).then(function(success) {
    self.send({
      response: 'nodeRenamed',
      success: !!success,
      error: success ? undefined : '节点不存在'
    });
  }, function(e) {
    self.send({
      response: 'nodeRenamed',
      success: false,
      error: (e && e.message) ? e.message : String(e || '未知错误')
    });
  });
};

export default interconnTree;
