package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun DraftRestoreDialog(
    show: Boolean,
    onRestore: () -> Unit,
    onDiscard: () -> Unit
) {
    // 先用本地可见态关弹窗、播退场动画，动画完全结束后（onDismissFinished）再执行动作。
    // 直接执行的话，「恢复」会立刻载入草稿触发编辑器大重组，主线程被占住，
    // 退场动画的帧被全部跳过，表现为弹窗瞬间消失没有动画。
    var visible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(show) {
        if (show) {
            visible = true
            pendingAction = null
        }
    }
    OverlayDialog(
        show = visible,
        title = "恢复草稿",
        summary = "检测到上次编辑未保存的内容，是否恢复到编辑器？",
        onDismissRequest = { /* 必须显式选择，禁止外部关闭 */ },
        onDismissFinished = {
            pendingAction?.let { it() }
            pendingAction = null
        }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "丢弃",
                onClick = {
                    visible = false
                    pendingAction = onDiscard
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "恢复",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    visible = false
                    pendingAction = onRestore
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}