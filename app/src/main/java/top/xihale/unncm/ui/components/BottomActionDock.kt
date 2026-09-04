package top.xihale.unncm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val DockContainerShape = RoundedCornerShape(percent = 50)
private val DockCtaHeight = 52.dp

/**
 * 底部悬浮操作 Dock（参考 crossscale 经典设计）：
 * - 悬浮在屏幕底部；
 * - 队列有文件或处理中时显现；
 * - 转换中变为“停止”，避免用户不可控；
 * - 空闲状态为“开始转换 (N)”。
 */
@Composable
fun BottomActionDock(
    isConverting: Boolean,
    pendingCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = pendingCount > 0 || isConverting,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = DockContainerShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 6.dp
            ) {
                Button(
                    onClick = if (isConverting) onStop else onStart,
                    colors = if (isConverting) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    },
                    shape = DockContainerShape,
                    modifier = Modifier
                        .height(DockCtaHeight)
                        .padding(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isConverting) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = if (isConverting) "停止转换" else "开始转换 ($pendingCount)",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}
