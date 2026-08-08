package com.silenthong.kdreader.logic;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TXT file transfer handler — matches the Vela app's interconnfile.js protocol.
 *
 * <p>All messages use the tag-based routing of handshake.js/interconn.js:
 * <ul>
 *   <li>Outgoing (Android → Watch): {@code { tag:"file", stat:"...", ...payload }}
 *   <li>Incoming (Watch → Android): {@code { tag:"file", type:"...", ...payload }}
 * </ul>
 *
 * <h3>Protocol (single chapter — the entire file is one chapter)</h3>
 * <pre>
 *   A→W: { tag:"file", stat:"startTransfer", filename, total:1, wordCount, startFrom:0 }
 *   W→A: { tag:"file", type:"ready", count:0, usage:0 }
 *   A→W: { tag:"file", stat:"d", count:0, data:"{index:0,name,content,wordCount,chunkNum,totalChunks}" }
 *   W→A: { tag:"file", type:"next_chunk" }                            // not last chunk
 *   ... repeat for each chunk ...
 *   W→A: { tag:"file", type:"chapter_chunk_complete" }                // last chunk
 *   A→W: { tag:"file", stat:"chapter_complete", count:0 }
 *   W→A: { tag:"file", type:"chapter_saved", count:1, syncedCount:1, totalCount:1, progress:100 }
 *   A→W: { tag:"file", stat:"transfer_complete" }
 *   W→A: { tag:"file", type:"transfer_finished" }                     // done!
 * </pre>
 */
public class TxtTransferHandler {

    private static final String TAG = "TxtTransfer";

    /** Characters per chunk — must match the watch side (interconnfile.js). */
    private static final int CHUNK_SIZE = 8000;

    /** Per-step response timeout (ms). */
    private static final long RESPONSE_TIMEOUT = 15000L;

    // Outgoing stat values
    private static final String STAT_START_TRANSFER  = "startTransfer";
    private static final String STAT_DATA            = "d";
    private static final String STAT_CHAPTER_COMPLETE = "chapter_complete";
    private static final String STAT_TRANSFER_COMPLETE = "transfer_complete";
    private static final String STAT_CANCEL           = "cancel";

    // Incoming type values (watch responses)
    private static final String TYPE_READY                 = "ready";
    private static final String TYPE_NEXT_CHUNK            = "next_chunk";
    private static final String TYPE_CHAPTER_CHUNK_COMPLETE = "chapter_chunk_complete";
    private static final String TYPE_CHAPTER_SAVED        = "chapter_saved";
    private static final String TYPE_TRANSFER_FINISHED    = "transfer_finished";
    private static final String TYPE_ERROR                = "error";
    private static final String TYPE_CANCEL               = "cancel";

    // ==================== Formula transfer (startFormula 链路) ====================
    // 公式 PNG 推送使用独立的状态机（同步阻塞），与 TXT 的异步状态机互斥运行。
    // 协议与 Snapnotes JsonFilePusher.kt / 手环端 app.ux 的 startFormula 分支对齐：
    //   A→W: { tag:"file", stat:"startFormula", subject, id, filename, w, h, totalChunks, totalBytes }
    //   W→A: { tag:"file", type:"ready", nextChunkIndex }
    //   A→W: { tag:"file", stat:"d", chunkIndex, totalChunks, data }   (data 为 base64 字符串)
    //   W→A: { tag:"file", type:"next_chunk" }                         (每片后)
    //   A→W: { tag:"file", stat:"transferComplete" }
    //   W→A: { tag:"file", type:"transfer_finished" }
    // 注意：stat:"transferComplete" 是驼峰，与 TXT 的 "transfer_complete" 不同；
    //       响应 type（ready/next_chunk/transfer_finished）与 TXT 重名，但手环端按
    //       startFormula/startTransfer 进入的不同模式分别处理，手机端用 formulaTransferring
    //       标志位把响应路由到公式状态机，不触碰 TXT 逻辑。

    /** 公式分片大小：10KB（与 Snapnotes JsonFilePusher 一致）。 */
    private static final int FORMULA_CHUNK_SIZE = 10 * 1024;

    /** 公式首包（startFormula）发送与等待 ready 的超时。 */
    private static final long FORMULA_FIRST_PACKET_TIMEOUT = 15000L;

    /** 公式每片发送与等待 next_chunk / transfer_finished 的超时。 */
    private static final long FORMULA_PER_CHUNK_TIMEOUT = 10000L;

    // Formula outgoing stat values
    private static final String STAT_START_FORMULA        = "startFormula";
    private static final String STAT_TRANSFER_COMPLETE_F  = "transferComplete"; // 驼峰，区别于 TXT 的 transfer_complete

    private final WearableManager conn;

    // Transfer context — accessed from both the SDK callback thread and the UI thread.
    // All reads/writes of progressListener and transferring are guarded by 'this'.
    private String pendingFileName;
    private int pendingWordCount;
    private int currentChunkIndex;   // 0-based chunk currently in flight
    private int totalChunks;
    private List<String> chunkList;
    private volatile TransferProgressListener progressListener;
    private Runnable responseTimeoutRunnable;
    private volatile boolean transferring = false;

    /** Target folder ID on the watch (e.g. "bt_root" or "bt_folder_xxx"). */
    private String targetFolder = "bt_root";

    // ----- Formula transfer state (synchronous, latch-based) -----
    // 与 TXT 的异步状态机完全独立；formulaTransferring 为 true 时，handleWatchMessage
    // 会把响应路由到 handleFormulaMessage，不进入 TXT 状态机。
    private volatile boolean formulaTransferring = false;
    private volatile String formulaError = null;
    private volatile int formulaNextChunkIndex = 0;
    private int formulaCurrentChunkIndex = 0;
    private String formulaCurrentFileName = "formula.png";
    private List<String> formulaChunks;
    private CountDownLatch formulaReadyLatch;
    private CountDownLatch formulaNextChunkLatch;
    private CountDownLatch formulaFinishedLatch;

    /** Progress callback for the UI layer. */
    public interface TransferProgressListener {
        void onProgress(int sent, int total, String message);
        void onSuccess(String message);
        void onError(String error);
    }

    // ==================== Construction ====================

    public TxtTransferHandler(WearableManager conn) {
        this.conn = conn;

        // Register for "file" tag messages from the watch.
        conn.addListener(WearableManager.TAG_FILE, message -> {
            try {
                JSONObject msg = new JSONObject(message);
                String type = msg.optString("type", "");
                handleWatchMessage(type, msg);
            } catch (Exception e) {
                Log.e(TAG, "Message parse error", e);
            }
        });
    }

    // ==================== Public entry point ====================

    /**
     * Send a TXT file's content to the watch.
     *
     * @param fileName     file name without extension
     * @param content      full text content
     * @param targetFolder folder ID on the watch where the file should be saved
     *                     ("bt_root" for root, "bt_folder_xxx" for sub-folders)
     * @param listener     progress callback (may be null)
     */
    public void sendTxtFile(String fileName, String content,
                            String targetFolder, TransferProgressListener listener) {
        this.progressListener = listener;
        this.pendingFileName = fileName != null ? fileName : "untitled";
        this.pendingWordCount = content != null ? content.length() : 0;
        this.chunkList = splitContent(content, CHUNK_SIZE);
        this.totalChunks = chunkList.size();
        this.currentChunkIndex = 0;
        this.transferring = true;
        this.targetFolder = targetFolder != null ? targetFolder : "bt_root";

        if (listener != null) {
            listener.onProgress(0, totalChunks,
                    "开始传输: " + pendingFileName + " (" + totalChunks + " 片)");
        }

        // Step 1: send startTransfer and wait for "ready".
        sendStartTransfer();
    }

    // ==================== Outgoing messages ====================

    /**
     * Send { tag:"file", stat:"startTransfer", ... } and wait for "ready".
     * Includes the target folder so the watch knows where to save the file.
     */
    private void sendStartTransfer() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_START_TRANSFER);
            payload.put("filename", pendingFileName);
            payload.put("total", 1);           // single chapter
            payload.put("wordCount", pendingWordCount);
            payload.put("startFrom", 0);
            payload.put("folder", targetFolder);  // target folder on the watch
        } catch (Exception e) {
            fail("构建 startTransfer 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> startTransfer: " + pendingFileName
                + " chunks=" + totalChunks + " words=" + pendingWordCount
                + " folder=" + targetFolder);
        sendRaw(payload, "发送 startTransfer 失败", "等待 ready 超时");
    }

    /**
     * Send a data chunk: { tag:"file", stat:"d", count:0, data:"{...}" }
     * The inner data is a JSON string with chunk details.
     */
    private void sendDataChunk(int chunkNum) {
        if (chunkNum >= totalChunks) {
            return;
        }
        this.currentChunkIndex = chunkNum;
        String chunkContent = chunkList.get(chunkNum);

        // Build the inner data JSON string
        JSONObject innerData = new JSONObject();
        try {
            innerData.put("index", 0);          // chapter index (always 0 for single chapter)
            innerData.put("name", pendingFileName);
            innerData.put("content", chunkContent);
            innerData.put("wordCount", pendingWordCount);
            innerData.put("chunkNum", chunkNum);
            innerData.put("totalChunks", totalChunks);
        } catch (Exception e) {
            fail("构建数据分片失败: " + e.getMessage());
            return;
        }

        // Build the outer message
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_DATA);
            payload.put("count", 0);             // chapter index (always 0)
            payload.put("data", innerData.toString());  // double-encoded JSON string
        } catch (Exception e) {
            fail("构建数据消息失败: " + e.getMessage());
            return;
        }

        int percent = (int) (chunkNum * 100f / Math.max(1, totalChunks));
        Log.d(TAG, ">>> chunk " + (chunkNum + 1) + "/" + totalChunks);
        TransferProgressListener l = progressListener;
        if (l != null) {
            l.onProgress(chunkNum, totalChunks,
                    "传输中 " + percent + "% (" + (chunkNum + 1) + "/" + totalChunks + ")");
        }

        sendRaw(payload, "发送分片 " + (chunkNum + 1) + " 失败", "等待分片确认超时");
    }

    /**
     * Send { tag:"file", stat:"chapter_complete", count:0 }
     * Called after the watch confirms all chunks of the chapter are received.
     */
    private void sendChapterComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_CHAPTER_COMPLETE);
            payload.put("count", 0);    // chapter index
        } catch (Exception e) {
            fail("构建 chapter_complete 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> chapter_complete");
        TransferProgressListener l2 = progressListener;
        if (l2 != null) {
            l2.onProgress(totalChunks, totalChunks,
                    "等待手环保存...");
        }

        sendRaw(payload, "发送 chapter_complete 失败", "等待 chapter_saved 超时");
    }

    /**
     * Send { tag:"file", stat:"transfer_complete" }
     * Called after the watch confirms the chapter is saved.
     */
    private void sendTransferComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_TRANSFER_COMPLETE);
        } catch (Exception e) {
            fail("构建 transfer_complete 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> transfer_complete");
        sendRaw(payload, "发送 transfer_complete 失败", "等待 transfer_finished 超时");
    }

    /**
     * Send a raw JSON payload via the WearableManager and arm the per-step timeout.
     */
    private void sendRaw(JSONObject payload, String sendErrorMsg, String timeoutMsg) {
        conn.sendRawMessageWithCallback(payload.toString(), new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                startResponseTimeout(timeoutMsg);
            }

            @Override
            public void onError(String error) {
                fail(sendErrorMsg + ": " + error);
            }
        });
    }

    // ==================== Incoming message handling ====================

    private void handleWatchMessage(String type, JSONObject msg) {
        // 公式传输进行中时，所有 "file" 响应都路由到公式状态机，不进入 TXT 逻辑。
        // 公式与 TXT 传输互斥（不会同时进行），因此不影响 TXT 状态机行为。
        if (formulaTransferring) {
            handleFormulaMessage(type, msg);
            return;
        }

        if (!transferring) {
            return;
        }

        switch (type) {
            case TYPE_READY:
                handleReady(msg);
                break;
            case TYPE_NEXT_CHUNK:
                handleNextChunk(msg);
                break;
            case TYPE_CHAPTER_CHUNK_COMPLETE:
                handleChapterChunkComplete(msg);
                break;
            case TYPE_CHAPTER_SAVED:
                handleChapterSaved(msg);
                break;
            case TYPE_TRANSFER_FINISHED:
                handleTransferFinished(msg);
                break;
            case TYPE_ERROR:
                handleErrorMsg(msg);
                break;
            case TYPE_CANCEL:
                handleCancelledMsg(msg);
                break;
            default:
                Log.w(TAG, "Unknown watch message type: " + type);
                break;
        }
    }

    /** ready: the watch is ready; begin streaming chunks from index 0. */
    private void handleReady(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< ready");
        sendDataChunk(0);
    }

    /** next_chunk: the watch wants the next data chunk. */
    private void handleNextChunk(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< next_chunk");
        int next = currentChunkIndex + 1;
        if (next >= totalChunks) {
            // Shouldn't happen — watch should send chapter_chunk_complete for last chunk
            sendChapterComplete();
        } else {
            sendDataChunk(next);
        }
    }

    /** chapter_chunk_complete: the watch received the last chunk of the chapter. */
    private void handleChapterChunkComplete(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< chapter_chunk_complete");
        // Tell the watch to save the chapter
        sendChapterComplete();
    }

    /** chapter_saved: the watch saved the chapter to storage. */
    private void handleChapterSaved(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< chapter_saved");
        // Send transfer_complete to finalize
        sendTransferComplete();
    }

    /** transfer_finished: the watch confirmed the entire transfer is complete. */
    private void handleTransferFinished(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< transfer_finished");
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onSuccess("传输完成: " + pendingFileName);
        }
    }

    /** error: the watch reported an error. */
    private void handleErrorMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        String message = msg.optString("message", "手环传输错误");
        Log.e(TAG, "<<< error: " + message);
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError("手环错误: " + message);
        }
    }

    /** cancel: the watch cancelled the transfer. */
    private void handleCancelledMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< cancel");
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError("手环取消了传输");
        }
    }

    // ==================== Timeout management ====================

    private void startResponseTimeout(String timeoutMessage) {
        cancelResponseTimeout();
        responseTimeoutRunnable = () -> {
            Log.w(TAG, "Response timeout: " + timeoutMessage);
            transferring = false;
            sendCancelQuietly();
            TransferProgressListener l = progressListener;
            progressListener = null;
            if (l != null) {
                l.onError(timeoutMessage);
            }
        };
        conn.getHandler().postDelayed(responseTimeoutRunnable, RESPONSE_TIMEOUT);
    }

    private void cancelResponseTimeout() {
        if (responseTimeoutRunnable != null) {
            conn.getHandler().removeCallbacks(responseTimeoutRunnable);
            responseTimeoutRunnable = null;
        }
    }

    /**
     * Send { tag:"file", stat:"cancel" } without waiting for a response.
     */
    private void sendCancelQuietly() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_CANCEL);
            conn.sendRawMessageWithCallback(payload.toString(), null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send cancel", e);
        }
    }

    private void fail(String error) {
        cancelResponseTimeout();
        transferring = false;
        Log.e(TAG, error);
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError(error);
        }
    }

    // ==================== Utilities ====================

    /**
     * Split content into chunks of at most chunkSize characters.
     */
    private List<String> splitContent(String content, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            chunks.add(content.substring(start, end));
            start = end;
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }

    /**
     * Read TXT file content from a Uri (UTF-8).
     */
    public static String readTxtFromUri(Context context, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
        }
        return sb.toString();
    }

    /**
     * Strip the extension from a file name.
     */
    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null) return "untitled";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    /**
     * Cancel the current transfer (user-initiated).
     */
    public void cancelTransfer() {
        if (!transferring) {
            return;
        }
        cancelResponseTimeout();
        transferring = false;
        sendCancelQuietly();
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError("传输已取消");
        }
    }

    // ==================== Formula transfer (startFormula 链路) ====================

    /**
     * 公式 PNG 文件名：md5(subject#id) 十六进制前 12 位 + ".png"。
     *
     * <p>与手环内置 gen_formulas.js 命名算法一致；纯 ASCII 哈希命名避免中文/# 进 URI。
     * 移植自 Snapnotes JsonFilePusher.formulaFileName。
     */
    public String formulaFileName(String subject, int id) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((subject + "#" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i] & 0xFF));
            }
            return sb.toString() + ".png";
        } catch (Exception e) {
            Log.e(TAG, "formulaFileName md5 fail, fallback", e);
            // 兜底：用 hashCode 生成一个类哈希名，避免崩溃
            return Integer.toHexString((subject + "#" + id).hashCode()) + ".png";
        }
    }

    /**
     * 推送一个知识点公式 PNG（startFormula 链路）。同步阻塞方法，请在后台线程调用。
     *
     * <p>协议与 Snapnotes JsonFilePusher.pushFormula / 手环端 app.ux 的 startFormula 分支对齐：
     * startFormula（含 subject/id/filename/w/h）→ ready → d 分片（base64，≤10KB）→
     * 每片 next_chunk → transferComplete → transfer_finished。
     *
     * <p>PNG 先整体 base64 再按 ≤10KB 切字符串（不要先切二进制再编码）。
     *
     * @param subject  知识点所属科目（用于生成文件名）
     * @param id       知识点 id（用于生成文件名）
     * @param pngBytes 公式 PNG 字节（由 FormulaPngRenderer 渲染得到）
     * @param w        PNG 原始像素宽
     * @param h        PNG 原始像素高
     * @param listener 进度回调（可为 null；回调在调用线程触发，UI 更新需自行切主线程）
     */
    public void sendFormula(String subject, int id, byte[] pngBytes, int w, int h,
                            TransferProgressListener listener) {
        if (formulaTransferring) {
            if (listener != null) {
                listener.onError("上一次公式传输尚未结束，请稍候或重试");
            }
            return;
        }
        if (pngBytes == null || pngBytes.length == 0) {
            if (listener != null) {
                listener.onError("公式图片为空");
            }
            return;
        }

        String fileName = formulaFileName(subject, id);
        String base64Text = Base64.encodeToString(pngBytes, Base64.NO_WRAP);
        List<String> chunks = chunkUtf8Text(base64Text, FORMULA_CHUNK_SIZE);
        if (chunks.isEmpty()) {
            if (listener != null) {
                listener.onError("公式图片为空");
            }
            return;
        }

        final int totalChunks = chunks.size();
        final long totalBytes = pngBytes.length;

        formulaTransferring = true;
        formulaError = null;
        formulaCurrentFileName = fileName;
        formulaChunks = chunks;
        formulaCurrentChunkIndex = 0;
        formulaNextChunkIndex = 0;
        formulaReadyLatch = new CountDownLatch(1);
        formulaNextChunkLatch = new CountDownLatch(1);
        formulaFinishedLatch = new CountDownLatch(1);

        Log.e(TAG, "ALERT formula transfer start: " + subject + "#" + id + " -> "
                + fileName + " " + totalBytes + "B " + totalChunks + " chunks w=" + w + "x" + h);
        if (listener != null) {
            listener.onProgress(0, totalChunks, "准备发送公式图 " + subject + "#" + id);
        }

        try {
            // 1. 发 startFormula
            JSONObject start = new JSONObject();
            start.put("tag", WearableManager.TAG_FILE);
            start.put("stat", STAT_START_FORMULA);
            start.put("subject", subject);
            start.put("id", id);
            start.put("filename", fileName);
            start.put("w", w);
            start.put("h", h);
            start.put("totalChunks", totalChunks);
            start.put("totalBytes", totalBytes);

            sendPayloadSync(start, "发送 startFormula 失败", FORMULA_FIRST_PACKET_TIMEOUT);
            Log.e(TAG, "startFormula sent, waiting ready ...");

            // 2. 等 ready
            if (!formulaReadyLatch.await(FORMULA_FIRST_PACKET_TIMEOUT, TimeUnit.MILLISECONDS)) {
                throw new Exception("等待 ready 超时，手环未响应");
            }
            if (formulaError != null) {
                throw new Exception("手环错误: " + formulaError);
            }
            int startIdx = Math.max(0, Math.min(formulaNextChunkIndex, totalChunks - 1));
            formulaCurrentChunkIndex = startIdx;
            Log.e(TAG, "ready recv nextChunkIndex=" + formulaNextChunkIndex);

            // 3. 逐片发送 d
            while (formulaCurrentChunkIndex < totalChunks) {
                final int index = formulaCurrentChunkIndex;
                String chunkData = chunks.get(index);

                JSONObject chunk = new JSONObject();
                chunk.put("tag", WearableManager.TAG_FILE);
                chunk.put("stat", STAT_DATA);
                chunk.put("chunkIndex", index);
                chunk.put("totalChunks", totalChunks);
                chunk.put("data", chunkData);

                sendPayloadSync(chunk, "发送公式分片 " + (index + 1) + " 失败", FORMULA_PER_CHUNK_TIMEOUT);
                int pct = (int) ((index + 1) * 100L / totalChunks);
                if (listener != null) {
                    listener.onProgress(index + 1, totalChunks,
                            "公式传输中 " + pct + "% (" + (index + 1) + "/" + totalChunks + ")");
                }
                Log.e(TAG, "formula chunk " + (index + 1) + "/" + totalChunks
                        + " sent " + chunkData.getBytes(StandardCharsets.UTF_8).length + "B");

                if (index < totalChunks - 1) {
                    if (!formulaNextChunkLatch.await(FORMULA_PER_CHUNK_TIMEOUT, TimeUnit.MILLISECONDS)) {
                        throw new Exception("等待 next_chunk 超时");
                    }
                    if (formulaError != null) {
                        throw new Exception("手环错误: " + formulaError);
                    }
                    formulaNextChunkLatch = new CountDownLatch(1);
                    formulaCurrentChunkIndex = index + 1;
                } else {
                    // 最后一片：手环端即便最后一片也可能先回 next_chunk；若回了就吃掉，未回也不阻塞。
                    formulaNextChunkLatch.await(FORMULA_PER_CHUNK_TIMEOUT, TimeUnit.MILLISECONDS);
                    if (formulaError != null) {
                        throw new Exception("手环错误: " + formulaError);
                    }
                    formulaCurrentChunkIndex = totalChunks;
                }
            }

            // 4. 发 transferComplete（驼峰，区别于 TXT 的 transfer_complete）
            if (listener != null) {
                listener.onProgress(totalChunks, totalChunks, "公式传输中 正在让手环写盘");
            }
            JSONObject complete = new JSONObject();
            complete.put("tag", WearableManager.TAG_FILE);
            complete.put("stat", STAT_TRANSFER_COMPLETE_F);
            sendPayloadSync(complete, "发送 transferComplete 失败", FORMULA_PER_CHUNK_TIMEOUT);
            Log.e(TAG, "transferComplete sent, waiting transfer_finished ...");

            // 5. 等 transfer_finished
            if (!formulaFinishedLatch.await(FORMULA_PER_CHUNK_TIMEOUT, TimeUnit.MILLISECONDS)) {
                throw new Exception("等待 transfer_finished 超时");
            }
            if (formulaError != null) {
                throw new Exception("手环错误: " + formulaError);
            }
            Log.e(TAG, "transfer_finished recv ok");
            if (listener != null) {
                listener.onSuccess("公式传输完成: " + fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "formula transfer fail: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError("公式传输失败: " + e.getMessage());
            }
        } finally {
            resetFormulaState();
        }
    }

    /** 处理公式传输期间手环回包，唤醒对应 CountDownLatch。 */
    private void handleFormulaMessage(String type, JSONObject msg) {
        if (TYPE_READY.equals(type)) {
            formulaNextChunkIndex = msg.optInt("nextChunkIndex", 0);
            if (formulaReadyLatch != null) {
                formulaReadyLatch.countDown();
            }
            Log.d(TAG, "<<< formula ready nextChunkIndex=" + formulaNextChunkIndex);
        } else if (TYPE_NEXT_CHUNK.equals(type)) {
            if (formulaNextChunkLatch != null) {
                formulaNextChunkLatch.countDown();
            }
            Log.d(TAG, "<<< formula next_chunk");
        } else if (TYPE_TRANSFER_FINISHED.equals(type)) {
            if (formulaFinishedLatch != null) {
                formulaFinishedLatch.countDown();
            }
            Log.d(TAG, "<<< formula transfer_finished");
        } else if (TYPE_ERROR.equals(type)) {
            formulaError = msg.optString("message", "手环公式传输错误");
            // 唤醒所有可能阻塞的 latch，让等待方快速失败
            if (formulaReadyLatch != null) {
                formulaReadyLatch.countDown();
            }
            if (formulaNextChunkLatch != null) {
                formulaNextChunkLatch.countDown();
            }
            if (formulaFinishedLatch != null) {
                formulaFinishedLatch.countDown();
            }
            Log.e(TAG, "<<< formula error: " + formulaError);
        } else {
            Log.w(TAG, "Ignore formula message type: " + type + " (" + msg + ")");
        }
    }

    /**
     * 同步发送一条 JSON 消息：等发送完成回调（仅确认 BLE 下发，不等手环业务回包）。
     * 发送失败或超时抛 Exception，由调用方 catch。
     */
    private void sendPayloadSync(final JSONObject payload, String errorMsg, long timeoutMs) throws Exception {
        final CountDownLatch sendLatch = new CountDownLatch(1);
        final String[] err = new String[1];
        conn.sendRawMessageWithCallback(payload.toString(), new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                sendLatch.countDown();
            }

            @Override
            public void onError(String error) {
                err[0] = error;
                sendLatch.countDown();
            }
        });
        if (!sendLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new Exception("发送超时: " + errorMsg);
        }
        if (err[0] != null) {
            throw new Exception(errorMsg + ": " + err[0]);
        }
    }

    /** 复位公式传输状态，使下一次推送可正常开始。 */
    private void resetFormulaState() {
        formulaTransferring = false;
        formulaError = null;
        formulaChunks = null;
        formulaCurrentChunkIndex = 0;
        formulaNextChunkIndex = 0;
        formulaCurrentFileName = "formula.png";
        formulaReadyLatch = null;
        formulaNextChunkLatch = null;
        formulaFinishedLatch = null;
    }

    /**
     * 按 UTF-8 字节上限切片，同时不切断 Unicode 字符。移植自 Snapnotes chunkUtf8Text。
     * 对 base64（纯 ASCII）文本等价于按字节切，但通用实现保证对任意文本安全。
     */
    private List<String> chunkUtf8Text(String text, int maxBytes) {
        List<String> result = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        StringBuilder builder = new StringBuilder();
        int bytesInChunk = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String ch = new String(Character.toChars(codePoint));
            int charBytes = ch.getBytes(StandardCharsets.UTF_8).length;
            if (builder.length() > 0 && bytesInChunk + charBytes > maxBytes) {
                result.add(builder.toString());
                builder.setLength(0);
                bytesInChunk = 0;
            }
            builder.append(ch);
            bytesInChunk += charBytes;
            i += charCount;
        }
        if (builder.length() > 0) {
            result.add(builder.toString());
        }
        return result;
    }
}
