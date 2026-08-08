package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import com.whyy.snapnotes.ui.viewmodel.ExportResult

@Composable
fun ExportResultDialog(
    result: ExportResult?,
    onDismiss: () -> Unit
) {
    // 不用 if (result==null) return 拔组件，否则 OverlayDialog 退场动画来不及播。
    // 显示/隐藏交给 OverlayDialog 的 show；隐藏时 title/summary 用占位值。
    val visible = result != null
    val r = result
    OverlayDialog(
        title = if (r != null) {
            if (r.success) "导出成功" else "导出失败"
        } else "",
        summary = r?.message ?: "",
        show = visible,
        onDismissRequest = onDismiss
    ) {
        if (r != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = "知道了",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = onDismiss,
                    modifier = Modifier.width(160.dp)
                )
            }
        }
    }
}
