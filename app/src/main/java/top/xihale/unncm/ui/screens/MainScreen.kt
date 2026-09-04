package top.xihale.unncm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.xihale.unncm.ConversionUiState
import top.xihale.unncm.UiFile
import top.xihale.unncm.ui.components.BottomActionDock
import top.xihale.unncm.ui.components.FileListItem
import top.xihale.unncm.ui.components.HeroStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    pendingFiles: List<UiFile>,
    conversionStatus: ConversionUiState,
    folderName: String?,
    outputPath: String?,
    threads: Int,
    onThreadsChange: (Int) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFile: (UiFile) -> Unit,
    onClearAll: () -> Unit,
    onStartConversion: () -> Unit,
    onStopConversion: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    var threadMenuExpanded by remember { mutableStateOf(false) }
    val isConverting = conversionStatus is ConversionUiState.Converting
    val isScanning = conversionStatus is ConversionUiState.Scanning

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "unNCM",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // 1. 斜线分割舞台（完全参考 crossscale 的几何设计）
                HeroStage(
                    selectedCount = pendingFiles.size,
                    folderName = folderName,
                    outputPath = outputPath,
                    onPickFiles = onPickFiles,
                    onPickFolder = onPickFolder,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                // 2. 扫描/转换进度指示条（安静无侵略感）
                AnimatedVisibility(visible = isScanning || isConverting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }

                // 3. 极简列表控制条（仅在有文件时呈现）
                if (pendingFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "队列 (${pendingFiles.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 紧凑线程切换 FilterChip
                            Box {
                                FilterChip(
                                    selected = false,
                                    onClick = { threadMenuExpanded = true },
                                    label = { Text("$threads 线程 ▾") },
                                    enabled = !isConverting
                                )
                                DropdownMenu(
                                    expanded = threadMenuExpanded,
                                    onDismissRequest = { threadMenuExpanded = false }
                                ) {
                                    listOf(1, 2, 4, 8).forEach { count ->
                                        DropdownMenuItem(
                                            text = { Text("$count 线程" + if (count == 4) " (推荐)" else "") },
                                            onClick = {
                                                onThreadsChange(count)
                                                threadMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // 清空按钮
                            TextButton(
                                onClick = onClearAll,
                                enabled = !isConverting
                            ) {
                                Text(
                                    text = "清空",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 4. 纯净文件列表（无装饰性假图标，无“待处理”废话）
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = pendingFiles,
                        key = { it.uri.toString() }
                    ) { file ->
                        FileListItem(
                            file = file,
                            onRemove = { onRemoveFile(file) }
                        )
                    }
                }
            }

            // 5. 底部悬浮操作 Dock（参考 crossscale BottomActionDock）
            BottomActionDock(
                isConverting = isConverting,
                pendingCount = pendingFiles.size,
                onStart = onStartConversion,
                onStop = onStopConversion,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
