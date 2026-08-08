package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 文件夹创建对话框：输入文件夹名称后确认创建。
 */
@Composable
fun FolderCreationDialog(
    show: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        var folderName by remember { mutableStateOf("") }

        OverlayDialog(
            title = "创建文件夹",
            summary = "输入文件夹名称，将在应用知识库目录下创建",
            show = show,
            onDismissRequest = onDismiss
        ) {
            TextField(
                value = folderName,
                onValueChange = { folderName = it },
                modifier = Modifier.fillMaxWidth(),
                label = "文件夹名称",
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "取消",
                    onClick = {
                        folderName = ""
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            onConfirm(folderName.trim())
                            folderName = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(text = "创建", color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
