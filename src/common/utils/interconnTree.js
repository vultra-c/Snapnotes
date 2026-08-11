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
import knowledgeStore from '../knowledgeStore.js';

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
 * 合并 knowledgeStore 中的知识点科目（作为文件夹节点）和 dataManager 中的 BT 文件夹/文件
 */
interconnTree.prototype.handleGetTree = function(retryCount) {
  var self = this;
  retryCount = retryCount || 0;

  // 知识点数据由 app.ux onCreate 异步读取（file.readText），
  // 若尚未加载完成则等待重试，最多 10 次（约 3 秒）。
  if (typeof knowledgeStore.isLoaded === 'function' && !knowledgeStore.isLoaded()) {
    if (retryCount < 10) {
      console.log('[BT-Tree] knowledgeStore not loaded yet, retry ' + (retryCount + 1) + '/10');
      setTimeout(function() { self.handleGetTree(retryCount + 1); }, 300);
      return;
    }
    console.warn('[BT-Tree] knowledgeStore still not loaded after 10 retries, proceeding with empty data');
  }

  console.log('[BT-Tree] getTree request (retry=' + retryCount + ')');
  dataManager.getFolderTreeForBluetooth().then(function(btTree) {
    // 从 knowledgeStore 获取知识点科目，构造为文件夹节点
    var kdTree = [];
    try {
      var subjects = knowledgeStore.getSubjects();
      console.log('[BT-Tree] knowledgeStore subjects: ' + (subjects ? subjects.length : 0));
      for (var i = 0; i < subjects.length; i++) {
        var subj = subjects[i];
        var points = knowledgeStore.getKnowledge(subj.name);
        console.log('[BT-Tree] subject "' + subj.name + '": ' + (points ? points.length : 0) + ' points');
        var children = [];
        for (var j = 0; j < points.length; j++) {
          children.push({
            id: 'kd_point_' + i + '_' + j,
            name: points[j].title || ('知识点' + (j + 1)),
            type: 'content'
          });
        }
        kdTree.push({
          id: 'kd_subject_' + i,
          name: subj.name,
          type: 'folder',
          children: children
        });
      }
    } catch (e) {
      console.log('[BT-Tree] knowledgeStore merge error: ' + e);
    }

    var tree = kdTree.concat(btTree || []);
    console.log('[BT-Tree] tree total: ' + tree.length + ' nodes (kd=' + kdTree.length + ' bt=' + (btTree ? btTree.length : 0) + ')');
    self.send({
      response: 'treeData',
      tree: tree
    }).then(function() {
      console.log('[BT-Tree] Tree sent successfully');
    }, function() {
      console.log('[BT-Tree] send treeData failed');
    });
  }, function(e) {
    console.error('[BT-Tree] getTree error: ' + e);
    // 即使 dataManager 失败，也尝试发送 knowledgeStore 数据
    var kdTree = [];
    try {
      var subjects = knowledgeStore.getSubjects();
      for (var i = 0; i < subjects.length; i++) {
        var subj = subjects[i];
        var points = knowledgeStore.getKnowledge(subj.name);
        var children = [];
        for (var j = 0; j < points.length; j++) {
          children.push({
            id: 'kd_point_' + i + '_' + j,
            name: points[j].title || ('知识点' + (j + 1)),
            type: 'content'
          });
        }
        kdTree.push({
          id: 'kd_subject_' + i,
          name: subj.name,
          type: 'folder',
          children: children
        });
      }
    } catch (e2) {
      console.log('[BT-Tree] fallback knowledgeStore error: ' + e2);
    }
    self.send({
      response: 'treeData',
      tree: kdTree
    });
  });
};

/**
 * 处理创建文件夹请求
 */
interconnTree.prototype.handleCreateFolder = function(payload) {
  var self = this;
  var name = payload.name;
  var parentId = payload.parentId;
  var replied = false;
  console.log('[BT-Tree] createFolder: ' + name + ' parentId=' + (parentId || 'bt_root'));

  // 超时兜底：8s 内无论 createBluetoothFolder 是否 resolve 都保证回包，避免手机端干等超时
  var watchdog = setTimeout(function() {
    if (replied) return;
    replied = true;
    console.warn('[BT-Tree] createFolder TIMEOUT, sending fallback error');
    self.send({
      response: 'folderCreated',
      folderId: null,
      success: false,
      error: '手环端创建超时'
    });
  }, 8000);

  dataManager.createBluetoothFolder(name, parentId).then(function(folderId) {
    if (replied) return;
    replied = true;
    clearTimeout(watchdog);
    self.send({
      response: 'folderCreated',
      folderId: folderId,
      success: true
    });
  }, function(e) {
    if (replied) return;
    replied = true;
    clearTimeout(watchdog);
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
 * 知识点节点（kd_subject_ / kd_point_）不支持删除，返回错误提示
 */
interconnTree.prototype.handleDeleteNode = function(payload) {
  var self = this;
  var nodeId = payload.nodeId;
  console.log('[BT-Tree] deleteNode: ' + nodeId);

  // 知识点节点不支持删除（由手机端知识库管理，非手环文件系统）
  if (nodeId && (nodeId.indexOf('kd_subject_') === 0 || nodeId.indexOf('kd_point_') === 0)) {
    console.log('[BT-Tree] skip kd_ node delete: ' + nodeId);
    self.send({
      response: 'nodeDeleted',
      success: false,
      error: '知识点节点不支持在此删除，请到知识点管理中操作'
    });
    return;
  }

  dataManager.deleteBluetoothNode(nodeId).then(function(result) {
    var response = {
      response: 'nodeDeleted',
      success: !!result
    };
    // 成功回包不要带 undefined 字段，部分 Vela 固件会因此丢弃整个消息。
    if (!result) response.error = '节点不存在';
    self.send(response).then(function() {
      console.log('[BT-Tree] nodeDeleted response sent: success=' + (!!result));
    }, function(e) {
      console.log('[BT-Tree] nodeDeleted response send failed: ' + e);
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
 * 知识点节点（kd_subject_ / kd_point_）不支持重命名，返回错误提示
 * @param {string} nodeId 节点 ID
 * @param {string} newName 新名称
 */
interconnTree.prototype.handleRenameNode = function(payload) {
  var self = this;
  var nodeId = payload.nodeId;
  var newName = payload.newName;
  console.log('[BT-Tree] renameNode: ' + nodeId + ' -> ' + newName);

  // 知识点节点不支持重命名
  if (nodeId && (nodeId.indexOf('kd_subject_') === 0 || nodeId.indexOf('kd_point_') === 0)) {
    console.log('[BT-Tree] skip kd_ node rename: ' + nodeId);
    self.send({
      response: 'nodeRenamed',
      success: false,
      error: '知识点节点不支持重命名，请到知识点管理中操作'
    });
    return;
  }

  dataManager.renameBluetoothNode(nodeId, newName).then(function(success) {
    var response = {
      response: 'nodeRenamed',
      success: !!success
    };
    // 成功回包不要带 undefined 字段，部分 Vela 固件会因此丢弃整个消息。
    if (!success) response.error = '节点不存在';
    self.send(response).then(function() {
      console.log('[BT-Tree] nodeRenamed response sent: success=' + (!!success));
    }, function(e) {
      console.log('[BT-Tree] nodeRenamed response send failed: ' + e);
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
