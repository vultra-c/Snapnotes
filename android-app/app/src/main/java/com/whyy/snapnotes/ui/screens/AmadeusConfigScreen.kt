package com.whyy.snapnotes.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.viewmodel.AmadeusConfig
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlinx.coroutines.delay

/**
 * 手环端 Amadeus AI 聊天助手的配置页（手机端）。
 *
 * - 全部字段只存手机端 SharedPreferences（见 [com.whyy.snapnotes.ui.viewmodel.SnapNotesViewModel] 的
 *   Amadeus 段），手环不传不存不感知。详见根目录「手机端AI聊天适配说明.md」第五节。
 * - 本页只做 UI 填写 + 持久化：启用开关 + Base API（baseUrl/apiKey/model）。
 *   代理/超时仍在 prefs 里保留默认值供 [com.whyy.snapnotes.logic.AmadeusChat] 读取，但本页不再暴露编辑。
 * - 「启用」关闭时下方 API 项依旧可见可填（不灰化），但 [AmadeusConfig.isReady] 在未启用时
 *   永远为 false，主页入口卡片与 LLM 调用据此判「是否可用」。
 * - TopAppBar 右上角图标进「上下文管理菜单」（[AmadeusContextScreen]）：会话列表/查看/清空、
 *   本地测试发送、导出最近一次回复，便于观测 Amadeus 运行态与排查网络。
 *
 * @param onBackClick 返回主页（由 NavDisplay 的 onBack 兜底，本回调用于 TopAppBar 返回箭头）。
 * @param onOpenContext 打开上下文管理菜单。
 */
@Composable
fun AmadeusConfigScreen(
    config: AmadeusConfig,
    onEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onOpenContext: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 当前正在编辑的文本字段；非 null 时弹出对应编辑对话框。
    var editingField by remember { mutableStateOf<EditField?>(null) }

    BackHandler { onBackClick() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "Amadeus",
                largeTitle = "Amadeus",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenContext, modifier = Modifier.padding(end = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Notes, contentDescription = "上下文管理")
                    }
                }
            )
        },
        popupHost = {}
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            )
        ) {
            // 前置声明：Android 系统对息屏/锁屏后台发起网络请求有硬限制，
            // 黑屏后手环发消息可能收不到回复（亮屏后立即恢复）。非应用缺陷，告知用户设预期。
            // error 警示卡 + Report 图标：风格对齐 HomeScreen 连接失败卡。
            item {
                SmallTitle(text = "说明", modifier = Modifier.padding(top = 12.dp))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = MiuixIcons.Report,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "锁屏后台不可用",
                                style = MiuixTheme.textStyles.title3,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onErrorContainer,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Android 硬限制：屏幕熄灭后台时手机端无法新发起网络请求，"
                                + "手环消息会回复「调用失败」；手动亮屏后立即恢复。"
                                + "非锁屏状态下后台正常使用不受影响。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            item {
                SmallTitle(text = "基本", modifier = Modifier.padding(top = 12.dp))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    BasicComponent(
                        title = "启用 Amadeus",
                        summary = "关闭后手环端聊天不会发起调用",
                        endActions = {
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = onEnabledChange
                            )
                        }
                    )
                }
            }
            item {
                SmallTitle(text = "API")
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    BasicComponent(
                        title = "Base URL",
                        summary = config.baseUrl.ifBlank { "留空 = 走厂商默认" },
                        onClick = { editingField = EditField.BaseUrl }
                    )
                    BasicComponent(
                        title = "API Key",
                        summary = if (config.apiKey.isNotBlank()) "已设置（点此修改）" else "未设置",
                        onClick = { editingField = EditField.ApiKey }
                    )
                    BasicComponent(
                        title = "Model",
                        summary = config.model.ifBlank { "未设置" },
                        onClick = { editingField = EditField.Model }
                    )
                }
            }
        }
    }

    // 始终渲染对话框，通过 show 控制显隐，以触发 OverlayDialog 自带的退出动画
    AmadeusTextEditDialog(
        show = editingField != null,
        title = editingField?.title ?: "",
        label = editingField?.label ?: "",
        hint = editingField?.hint ?: "",
        initial = when (editingField) {
            EditField.BaseUrl -> config.baseUrl
            EditField.ApiKey -> config.apiKey
            EditField.Model -> config.model
            null -> ""
        },
        onDismiss = { editingField = null },
        onConfirm = { value ->
            editingField?.let { field ->
                when (field) {
                    EditField.BaseUrl -> onBaseUrlChange(value)
                    EditField.ApiKey -> onApiKeyChange(value)
                    EditField.Model -> onModelChange(value)
                }
            }
            editingField = null
        }
    )
}

/**
 * 文本编辑弹窗，使用 OverlayDialog 并利用其 show 参数控制显隐动画。
 * @param show 是否显示，为 false 时会播放退出动画后自动隐藏。
 */
@Composable
private fun AmadeusTextEditDialog(
    show: Boolean,
    title: String,
    label: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }

    // 当对话框显示时，重置文本为 initial 并请求焦点
    LaunchedEffect(show, initial) {
        if (show) {
            text = initial
            delay(80)
            focusRequester.requestFocus()
        }
    }

    OverlayDialog(
        title = title,
        summary = hint,
        show = show,              // 由外部控制，true 显示有入场动画，false 播放退出动画
        onDismissRequest = onDismiss,
        renderInRootScaffold = false
    ) {
        Column {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .imePadding(),
                singleLine = true,
                label = label
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { onConfirm(text.trim()) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 可弹窗编辑的文本项；集中管理标题/标签/提示文案，避免页面内散落字符串。 */
private enum class EditField(val title: String, val label: String, val hint: String) {
    BaseUrl("Base URL", "API 根地址", "留空走厂商默认，如 https://api.deepseek.com"),
    ApiKey("API Key", "密钥", "请输入API密钥"),
    Model("Model", "模型名", "如 deepseek-chat")
}