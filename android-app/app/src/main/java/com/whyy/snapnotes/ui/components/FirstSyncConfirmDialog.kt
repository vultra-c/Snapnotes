package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog


@Composable
fun FirstSyncConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var countdown by remember(show) { mutableIntStateOf(10) }
    val isCountingDown = countdown > 0

    LaunchedEffect(show) {
        if (show) {
            countdown = 10
            while (show && countdown > 0) {
                delay(1_000)
                countdown--
            }
        }
    }

    OverlayDialog(
        show = show,                      // Boolean，新版 API
        title = "同步确认",
        summary = "由于 Vela 优化问题，同步时手环重启为正常现象，开机后继续同步即可。\n\n首次同步报错为正常现象。\n若某文件同步一直报错，可重新选择并推送。" +
                if (isCountingDown) "\n\n请仔细阅读以上内容（${countdown} 秒后可继续）" else "",
        onDismissRequest = { /* 禁止外部关闭 */ }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = if (isCountingDown) "确认 ($countdown)" else "确认",
                enabled = !isCountingDown,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            )
        }
    }
}