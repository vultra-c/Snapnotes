package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.viewmodel.PushRecord
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 批量删除推送历史记录的确认框。
 *
 * 文案明确：删的是本机缓存与记录，不删手环上已导入的内容。
 */
@Composable
fun HistoryBatchDeleteConfirmDialog(
    records: List<PushRecord>?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val visible = records != null && records.isNotEmpty()
    val list = records
    OverlayDialog(
        title = "删除选中的 ${list?.size ?: 0} 条记录？",
        summary = if (list != null) {
            "这 ${list.size} 条记录将从本机推送历史中删除，本地缓存文件也会清掉。" +
                "这不会删除手环上已经导入的内容。"
        } else "",
        show = visible,
        onDismissRequest = onDismiss
    ) {
        if (list != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "若仍想清掉手环上对应内容，请转至手环端导入知识点页手动删除。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            TextButton(
                text = "仍然删除",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
