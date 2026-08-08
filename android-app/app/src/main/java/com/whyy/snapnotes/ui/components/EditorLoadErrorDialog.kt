package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog   // 关键修改
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun EditorLoadErrorDialog(
    message: String?,
    onDismiss: () -> Unit
) {
    // 关键：Always in composition。把显示/隐藏完全交给 OverlayDialog 的 show：
    // show 由 true→false 时 OverlayDialog 内部播退场动画。若这里用 if (message==null) return
    // 在外层把组件拔掉，组件瞬间消失、退场动画来不及放——这正是先前各 Dialog 无退场的原因。
    OverlayDialog(
        title = "加载失败",
        summary = message ?: "",
        show = message != null,
        onDismissRequest = onDismiss
    ) {
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请确认文件是 UTF-8 编码、顶层为 { \"科目名\": [条目...] } 结构的合法 JSON。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            TextButton(
                text = "知道了",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}