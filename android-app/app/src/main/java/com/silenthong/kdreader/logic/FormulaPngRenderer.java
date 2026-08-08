package com.silenthong.kdreader.logic;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 公式 PNG 离屏渲染器：WebView + KaTeX 0.18.1（与手环内置公式图同源同版本）。
 *
 * <p>这是 Snapnotes {@code FormulaPngRenderer.kt} 的 Java 移植版，去掉了 Kotlin
 * 协程依赖，改用 {@link CountDownLatch} + 主线程 {@link Handler} 实现“同步阻塞”渲染。
 *
 * <p>渲染 HTML 结构与手环端 {@code scripts/gen_formulas.js} 对齐：
 * <ul>
 *   <li>336px 设计宽（按需由内容撑宽，与内置一致）</li>
 *   <li>透明底 + 白字（.katex 强制白色，适配手环深色内容页）</li>
 *   <li>formula-block padding 8px 24px 16px 24px，一条公式一个块，垂直堆叠</li>
 *   <li>KaTeX 资源从 assets/katex 加载（file:///android_asset/katex/）</li>
 * </ul>
 *
 * <p>截图方式：页面内用 html2canvas 把已渲染好的公式 DOM 直接绘制成 canvas，
 * 再 {@code canvas.toDataURL('image/png')} 把 PNG 以 base64 回传。
 * 全程不依赖窗口合成/SurfaceFlinger。
 *
 * <p>输出：PNG 字节 + 原始像素宽高（w/h 随 startFormula 发给手环做等比缩放）。
 * 渲染失败（KaTeX throwOnError 或超时）返回 null，由调用方跳过该知识点，不阻塞推送。
 *
 * <p><b>线程模型：</b>{@link #render(List)} 是同步阻塞方法，必须在后台线程调用。
 * 内部通过 {@link Handler}(主线程) 创建/操作 WebView，并用 CountDownLatch 把异步结果
 * 同步回调用线程。所有渲染请求通过 {@code synchronized(renderLock)} 串行化。
 */
public class FormulaPngRenderer {

    private static final String TAG = "FormulaPngRenderer";

    /** 默认渲染宽度（手环屏宽，与内置 gen_formulas.js 一致）。 */
    public static final int DEFAULT_WIDTH_PX = 336;

    /** 最大渲染高度，防止异常内容撑出超大画布。 */
    private static final int MAX_HEIGHT_PX = 3000;

    /** 字体就绪 + html2canvas 截图的整体超时（毫秒）。 */
    private static final long FONT_READY_TIMEOUT_MS = 8000L;

    /** 收到首帧结果后静默等待时间，容忍 html2canvas 多次回传。 */
    private static final long SETTLE_DELAY_MS = 700L;

    /** onPageFinished 后延迟取一次 window.__snapResult 的兜底延迟。 */
    private static final long PAGE_FINISHED_PROBE_DELAY_MS = 150L;

    private static final String PNG_DATA_PREFIX = "data:image/png;base64,";

    /** PNG 渲染结果：字节 + 原始像素宽高。 */
    public static class PngResult {
        public final byte[] bytes;
        public final int width;
        public final int height;

        public PngResult(byte[] bytes, int width, int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }
    }

    /** 渲染详情：成功时 png 非空；失败时 errorMessages 携带具体报错。 */
    public static class RenderDetail {
        public final PngResult png;
        public final List<String> errorMessages;

        public RenderDetail(PngResult png, List<String> errorMessages) {
            this.png = png;
            this.errorMessages = errorMessages != null ? errorMessages : Collections.<String>emptyList();
        }
    }

    /** 单次渲染的测量/截图结果（内部使用）。 */
    private static final class MeasureResult {
        final int w;
        final int h;
        final List<String> errors;
        final String dataUrl;

        MeasureResult(int w, int h, List<String> errors, String dataUrl) {
            this.w = w;
            this.h = h;
            this.errors = errors != null ? errors : Collections.<String>emptyList();
            this.dataUrl = dataUrl;
        }
    }

    /** JS Bridge 回调接口。 */
    private interface BridgeCallback {
        void onResult(String json);
    }

    /**
     * JS Bridge：JS 里 {@code SnapBridge.onResult(json)} 同步回调（WebView 内部线程）。
     */
    private final class SnapBridge {
        @JavascriptInterface
        public void onResult(String json) {
            BridgeCallback cb = bridgeCallback;
            if (cb != null) {
                cb.onResult(json);
            }
        }
    }

    /**
     * 单次渲染会话状态：封装 CountDownLatch 与结果持有，确保多次渲染互不干扰。
     * 所有字段仅由主线程访问（result 例外，加 volatile 供调用线程读取）。
     */
    private final class RenderSession {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile MeasureResult result;
        volatile boolean pending = true;
        Runnable settleRunnable;

        /** 处理 JS Bridge 或 evaluateJavascript 回传的 JSON（可能来自任意线程，切回主线程）。 */
        void handleJson(final String json) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!pending) {
                        return;
                    }
                    MeasureResult r = parseMeasure(json);
                    if (r == null) {
                        return;
                    }
                    result = r;
                    scheduleSettle();
                }
            });
        }

        /** 收到结果后延迟 SETTLE_DELAY_MS 静默，期间若有新结果则重新计时。 */
        void scheduleSettle() {
            if (settleRunnable != null) {
                mainHandler.removeCallbacks(settleRunnable);
            }
            settleRunnable = new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            };
            mainHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS);
        }

        /** 结束本次会话：释放 bridge 回调并唤醒调用线程。幂等。 */
        void finish() {
            if (pending) {
                pending = false;
                bridgeCallback = null;
                latch.countDown();
            }
        }
    }

    private final Context activityContext;

    /** density=1 的 Context 缓存（WebView 必须用它创建，CSS px 才等于物理 px）。 */
    private final Context fixedContext;

    /** 主线程 Handler，用于创建/操作 WebView。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 串行化所有渲染（编辑预览与推送共用同一个 renderer，WebView 复用需互斥）。 */
    private final Object renderLock = new Object();

    private WebView webView;

    /** 供 JS 把测量/截图结果同步回传到 Java（JS Bridge，比 evaluateJavascript 回调可靠）。 */
    private volatile BridgeCallback bridgeCallback;

    public FormulaPngRenderer(Context activityContext) {
        this.activityContext = activityContext;
        this.fixedContext = createFixedDensityContext(activityContext);
    }

    /**
     * 把多条 LaTeX 公式渲染成一张 PNG（垂直堆叠）。同步阻塞方法，请在后台线程调用。
     *
     * @param latexList 已转换好的 LaTeX 列表（一条公式一个元素）
     * @return 渲染结果；任一条公式 KaTeX 渲染失败、或超时/异常时返回 null
     */
    public PngResult render(List<String> latexList) {
        RenderDetail detail = renderDetail(latexList, false);
        return detail != null ? detail.png : null;
    }

    /**
     * 渲染并返回详情（含错误消息）。同步阻塞方法，请在后台线程调用。
     *
     * @param latexList   LaTeX 列表
     * @param previewMode true 为手机端预览（收缩宽度 + 放大字号 + 2x 分辨率）；
     *                    false 为推送到手环（固定 336px 宽，与内置 gen_formulas.js 规格一致）
     * @return 渲染详情；输入为空返回 null
     */
    public RenderDetail renderDetail(final List<String> latexList, final boolean previewMode) {
        if (latexList == null || latexList.isEmpty()) {
            return null;
        }
        synchronized (renderLock) {
            final RenderSession session = new RenderSession();

            // 在主线程创建/复用 WebView 并加载 HTML。
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRender(session, latexList, previewMode);
                }
            });

            // 阻塞等待结果：比主线程超时多 2s 余量，确保主线程超时先触发。
            try {
                session.latch.await(FONT_READY_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 兜底：确保会话结束（若主线程超时尚未执行，则补一刀；幂等）。
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    session.finish();
                }
            });

            MeasureResult result = session.result;
            if (result == null) {
                Log.e(TAG, "renderDetail: measure result null (timeout), latex=" + latexList);
                return new RenderDetail(null, listOf("渲染超时，请重试"));
            } else if (!result.errors.isEmpty()) {
                Log.e(TAG, "katex render errors on formulas: " + result.errors);
                return new RenderDetail(null, result.errors);
            } else {
                int w = result.w;
                int h = result.h;
                String dataUrl = result.dataUrl;
                if (w <= 0 || h <= 0 || h > MAX_HEIGHT_PX || dataUrl == null || dataUrl.isEmpty()) {
                    Log.e(TAG, "render size/data invalid: " + w + "x" + h
                            + " dataUrl=" + (dataUrl == null ? "null" : dataUrl.length()));
                    return new RenderDetail(null, listOf("渲染尺寸异常，请重试"));
                } else {
                    byte[] bytes = decodePng(dataUrl);
                    if (bytes.length == 0) {
                        Log.e(TAG, "png base64 decode empty, skip");
                        return new RenderDetail(null, listOf("PNG 解码失败"));
                    } else {
                        Log.e(TAG, "formula png rendered " + w + "x" + h + " " + bytes.length + "B");
                        return new RenderDetail(new PngResult(bytes, w, h), new ArrayList<String>());
                    }
                }
            }
        }
    }

    /** 释放复用的 WebView（Activity 销毁时调用，防泄漏）。 */
    public void release() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView != null) {
                        webView.destroy();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "webview destroy fail", e);
                }
                webView = null;
                bridgeCallback = null;
            }
        });
    }

    // ==================== 内部实现 ====================

    /** 在主线程执行：创建/复用 WebView、装配回调、加载 HTML、挂超时。 */
    private void startRender(final RenderSession session,
                             final List<String> latexList,
                             final boolean previewMode) {
        try {
            final WebView wv;
            if (webView == null) {
                wv = new WebView(fixedContext);
                configureWebView(wv);
                webView = wv;
            } else {
                wv = webView;
            }

            // 安全视口尺寸：336dp 宽、足够高的文档，按内容自动撑开。
            wv.layout(0, 0, DEFAULT_WIDTH_PX, MAX_HEIGHT_PX);

            // 装配本次会话的 bridge 回调。
            bridgeCallback = new BridgeCallback() {
                @Override
                public void onResult(String json) {
                    session.handleJson(json);
                }
            };

            final String html = buildHtml(latexList, previewMode);

            // 页面加载兜底：若 JS 始终未回传，等 onPageFinished 后 evaluateJavascript 取一次。
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!session.pending) {
                                return;
                            }
                            wv.evaluateJavascript("window.__snapResult", new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    if (!session.pending) {
                                        return;
                                    }
                                    MeasureResult r = parseMeasure(value);
                                    if (r != null) {
                                        session.result = r;
                                        session.scheduleSettle();
                                    }
                                }
                            });
                        }
                    }, PAGE_FINISHED_PROBE_DELAY_MS);
                }
            });

            wv.loadDataWithBaseURL(
                    "file:///android_asset/katex/", html, "text/html", "utf-8", null);

            // 超时兜底：WebView 卡死/JS 永不回传时不能挂死推送流程。
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (session.pending) {
                        Log.e(TAG, "formula measure timeout, result=" + session.result);
                        session.finish();
                    }
                }
            }, FONT_READY_TIMEOUT_MS);
        } catch (Exception e) {
            Log.e(TAG, "render setup fail", e);
            session.finish();
        }
    }

    /**
     * 构造 density=1 的 Context：让 336dp 布局 == 336px 物理像素，
     * 渲染出的 PNG 像素尺寸与内置公式图（deviceScaleFactor=1）一致。
     */
    private Context createFixedDensityContext(Context base) {
        final Context appContext = base.getApplicationContext();
        final Resources original = appContext.getResources();
        final DisplayMetrics fixedMetrics = new DisplayMetrics();
        fixedMetrics.setTo(original.getDisplayMetrics());
        fixedMetrics.density = 1f;
        fixedMetrics.scaledDensity = 1f;
        fixedMetrics.densityDpi = DisplayMetrics.DENSITY_MEDIUM;
        final AssetManager assets = appContext.getAssets();
        final Configuration config = new Configuration(original.getConfiguration());
        final Resources fixedResources = new Resources(assets, fixedMetrics, config);
        return new ContextWrapper(appContext) {
            @Override
            public Resources getResources() {
                return fixedResources;
            }
        };
    }

    private void configureWebView(WebView webView) {
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                DEFAULT_WIDTH_PX,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        android.webkit.WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(true);
        settings.setBlockNetworkImage(true);
        settings.setBlockNetworkLoads(true);
        // JS Bridge：渲染结果通过 addJavascriptInterface 同步回传，比 evaluateJavascript 回调可靠。
        webView.addJavascriptInterface(new SnapBridge(), "SnapBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }
        });
    }

    /**
     * HTML 与手环端 gen_formulas.js 输出对齐：katex.min.css + 白字 + formula-block 垂直堆叠。
     * KaTeX 渲染在页面内联 JS 同步执行（throwOnError），失败条目记入 __snapErrors（含消息）。
     * 字体就绪后用 html2canvas 把 #wrap 绘制成 canvas，toDataURL('image/png') 得 base64，
     * 连同 w/h/errors 经 SnapBridge 回传。
     */
    private String buildHtml(List<String> latexList, boolean previewMode) {
        String jsonArray = buildJsonArrayString(latexList);
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < latexList.size(); i++) {
            blocks.append("<div class=\"formula-block\"><span class=\"formula-slot\" id=\"f")
                  .append(i)
                  .append("\"></span></div>\n");
        }
        String wrapStyle = previewMode
                ? "#wrap{display:inline-block;}"
                : "#wrap{display:block;width:336px;}";
        String bodyFontSize = previewMode ? "font-size:36px;" : "";
        int canvasScale = previewMode ? 2 : 1;

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<link rel=\"stylesheet\" href=\"katex.min.css\">"
                + "<style>"
                + "html,body{margin:0;padding:0;background:transparent;" + bodyFontSize + "}"
                + wrapStyle
                + ".katex{color:#FFFFFF!important;}"
                + ".formula-block{margin:0;padding:8px 24px 16px 24px;}"
                + ".formula-slot{display:block;}"
                + "</style></head>"
                + "<body><div id=\"wrap\">" + blocks + "</div>"
                + "<script src=\"katex.min.js\"></script>"
                + "<script src=\"html2canvas.min.js\"></script>"
                + "<script>"
                + "window.__snapErrors = [];"
                + "window.__snapResult = null;"
                + "var FORMULAS = " + jsonArray + ";"
                + "(function() {"
                + "  for (var i = 0; i < FORMULAS.length; i++) {"
                + "    try {"
                + "      var el = document.getElementById('f' + i);"
                + "      el.innerHTML = katex.renderToString(FORMULAS[i], {displayMode: true, throwOnError: true, output: 'html'});"
                + "    } catch (e) {"
                + "      window.__snapErrors.push({i: i, m: String(e && e.message || e)});"
                + "    }"
                + "  }"
                + "})();"
                + "function snapWrite(r) {"
                + "  try { window.__snapResult = r; } catch (e) {}"
                + "  try { SnapBridge.onResult(r); } catch (e) {}"
                + "}"
                + "function snapCapture() {"
                + "  if (window.__snapErrors.length > 0) {"
                + "    snapWrite(JSON.stringify({w: 0, h: 0, dataUrl: '', errors: window.__snapErrors}));"
                + "    return;"
                + "  }"
                + "  var el = document.getElementById('wrap');"
                + "  var w = Math.max(1, Math.ceil(el.scrollWidth));"
                + "  var h = Math.max(1, Math.ceil(el.scrollHeight));"
                + "  if (typeof html2canvas !== 'function') {"
                + "    snapWrite(JSON.stringify({w: 0, h: 0, dataUrl: '', errors: [{i: -1, m: 'html2canvas not loaded'}]}));"
                + "    return;"
                + "  }"
                + "  html2canvas(el, {"
                + "    scale: " + canvasScale + ","
                + "    backgroundColor: null,"
                + "    logging: false,"
                + "    windowWidth: Math.max(336, w),"
                + "    windowHeight: Math.max(40, h)"
                + "  }).then(function(canvas) {"
                + "    var dataUrl = canvas.toDataURL('image/png');"
                + "    var result = JSON.stringify({w: canvas.width, h: canvas.height, dataUrl: dataUrl, errors: []});"
                + "    snapWrite(result);"
                + "  }).catch(function(err) {"
                + "    var result = JSON.stringify({w: 0, h: 0, dataUrl: '', errors: [{i: -1, m: String(err && err.message || err)}]});"
                + "    snapWrite(result);"
                + "  });"
                + "}"
                + "(function() {"
                + "  if (document.fonts && document.fonts.ready && document.fonts.ready.then) {"
                + "    document.fonts.ready.then(snapCapture).catch(snapCapture);"
                + "  } else {"
                + "    setTimeout(snapCapture, 300);"
                + "  }"
                + "})();"
                + "</script></body></html>";
    }

    /** 用 org.json 构造 JSON 数组字符串，正确转义 LaTeX 中的引号/反斜杠。 */
    private String buildJsonArrayString(List<String> latexList) {
        JSONArray arr = new JSONArray();
        if (latexList != null) {
            for (String s : latexList) {
                arr.put(s == null ? "" : s);
            }
        }
        return arr.toString();
    }

    /**
     * 解析 JS 回传的测量/截图结果。
     *
     * <p>bridge 回传的是原始 JSON 字符串（如 {@code {"w":336,...}}）；
     * evaluateJavascript 兜底回传的是被引号包裹的 JS 字符串字面量（如 {@code "{\"w\":336,...}"}），
     * 需先反引号再解析。
     */
    private MeasureResult parseMeasure(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty() || text.equals("null")) {
            return null;
        }
        // evaluateJavascript 兜底返回的 JS 字符串是带引号包裹的，需先反引号。
        if (text.startsWith("\"") && text.endsWith("\"")) {
            try {
                Object o = new JSONTokener(text).nextValue();
                if (o instanceof String) {
                    text = (String) o;
                } else {
                    return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "unquote measure fail: " + text, e);
                return null;
            }
        }
        try {
            JSONObject obj = new JSONObject(text);
            List<String> errors = new ArrayList<String>();
            JSONArray errArr = obj.optJSONArray("errors");
            if (errArr != null) {
                for (int i = 0; i < errArr.length(); i++) {
                    JSONObject item = errArr.optJSONObject(i);
                    if (item != null) {
                        String m = item.optString("m", null);
                        if (m != null && !m.equals("null")) {
                            errors.add(m);
                        }
                    }
                }
            }
            int w = (int) obj.optLong("w", 0L);
            int h = (int) obj.optLong("h", 0L);
            String dataUrl = obj.optString("dataUrl", null);
            if (dataUrl == null || dataUrl.equals("null") || dataUrl.isEmpty()) {
                dataUrl = null;
            }
            return new MeasureResult(w, h, errors, dataUrl);
        } catch (Exception e) {
            Log.e(TAG, "parse measure fail: " + text, e);
            return null;
        }
    }

    /** 从 {@code data:image/png;base64,XXXX} 解码出 PNG 字节。 */
    private byte[] decodePng(String dataUrl) {
        if (dataUrl == null) {
            return new byte[0];
        }
        int idx = dataUrl.indexOf(PNG_DATA_PREFIX);
        String payload = (idx >= 0) ? dataUrl.substring(idx + PNG_DATA_PREFIX.length()) : "";
        if (payload.isEmpty()) {
            Log.e(TAG, "png dataUrl prefix mismatch");
            return new byte[0];
        }
        try {
            return Base64.decode(payload, Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "png base64 decode fail", e);
            return new byte[0];
        }
    }

    private static List<String> listOf(String s) {
        List<String> l = new ArrayList<String>(1);
        l.add(s);
        return l;
    }
}
