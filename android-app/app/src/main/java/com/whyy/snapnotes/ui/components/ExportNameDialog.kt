package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import kotlinx.coroutines.delay

/**
 * 导出命名对话框：让用户输入文件名后确认。
 * @param defaultName 默认文件名（不含扩展名也可以，函数内补 .json）
 * @param onConfirm 返回最终文件名（保证以 .json 结尾）
 */
@Composable
fun ExportNameDialog(
    show: Boolean,
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String) -> Unit
) {
    var name by remember(show) { mutableStateOf(defaultName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(show) {
        if (show) {
            name = defaultName
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 不用 if (!show) return 外层拔组件——那样 OverlayDialog 看不到 show=true→false 的翻转，
    // 退场动画无法触发。显示/隐藏完全交给 OverlayDialog 的 show。
    OverlayDialog(
        title = "导出文件名",
        summary = "为导出的 JSON 文件命名（自动补 .json）",
        show = show,
        onDismissRequest = onDismiss,
        renderInRootScaffold = false  // 保持当前 Scaffold 内，避免被底部导航遮挡
    ) {
        Column {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .imePadding(),  // 键盘弹出时输入框上移
                singleLine = true,
                label = "文件名"
            )
            Spacer(Modifier.height(12.dp))  // 垂直间距
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "下一步",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val clean = name.trim().ifBlank { defaultName }
                        val withExt = if (clean.endsWith(".json", ignoreCase = true)) clean else "$clean.json"
                        onConfirm(withExt)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}