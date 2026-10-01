package top.xihale.unncm.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 1/e：斜线上下端点在顶/底边框上的位置比例（参考 crossscale 几何设计）。 */
private const val INV_E = 0.36787944f

/** 斜线分割形状：斜线左侧（单文件/多选文件区）。 */
private val FileZoneShape = SlashSideShape(isLeft = true)

/** 斜线分割形状：斜线右侧（文件夹区）。 */
private val FolderZoneShape = SlashSideShape(isLeft = false)

/**
 * 斜线单侧区域：下端点在底边自左 1/e 处，上端点在顶边自右 1/e 处（关于中心严格对称）；
 * isLeft 取线左侧多边形，否则取右侧。
 */
private class SlashSideShape(private val isLeft: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val topX = size.width * (1f - INV_E)
        val bottomX = size.width * INV_E
        val path = Path()
        if (isLeft) {
            path.moveTo(0f, 0f)
            path.lineTo(topX, 0f)
            path.lineTo(bottomX, size.height)
            path.lineTo(0f, size.height)
        } else {
            path.moveTo(topX, 0f)
            path.lineTo(size.width, 0f)
            path.lineTo(size.width, size.height)
            path.lineTo(bottomX, size.height)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * 斜线双区舞台：
 * 空态：1/e 斜线优雅分割，左侧选文件，右侧选文件夹，纯几何形态交互，零口水文案；
 * 选中态：高度收缩为紧凑概览卡片，显示当前源与快速重选入口。
 */
@Composable
fun HeroStage(
    selectedCount: Int,
    folderName: String?,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(if (selectedCount == 0 && folderName == null) 160.dp else 96.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        if (selectedCount == 0 && folderName == null) {
            EmptyStage(
                onPickFiles = onPickFiles,
                onPickFolder = onPickFolder
            )
        } else {
            PopulatedStage(
                selectedCount = selectedCount,
                folderName = folderName,
                onPickFiles = onPickFiles,
                onPickFolder = onPickFolder
            )
        }
    }
}

/**
 * 空态：一条 "/" 细线安静地分割舞台（端点位于上下边框 1/e 处），
 * 左侧=选文件、右侧=选目录，点按对应区域触发相应选择。
 */
@Composable
private fun EmptyStage(
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dx = maxWidth / 4

        // 左侧：选择文件
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(FileZoneShape)
                .clickable(role = Role.Button, onClick = onPickFiles),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AudioFile,
                contentDescription = "选择 NCM 文件",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .offset(x = -dx)
                    .size(38.dp)
            )
        }

        // 右侧：选择文件夹
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(FolderZoneShape)
                .clickable(role = Role.Button, onClick = onPickFolder),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = "选择文件夹",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .offset(x = dx)
                    .size(38.dp)
            )
        }

        // 对角细分割线
        val lineColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = lineColor,
                start = Offset(size.width * INV_E, size.height),
                end = Offset(size.width * (1f - INV_E), 0f),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/**
 * 选中态：纯净简练的概览，显示当前源并提供快速重新选择。
 */
@Composable
private fun PopulatedStage(
    selectedCount: Int,
    folderName: String?,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = folderName ?: "已载入 $selectedCount 个音频",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (folderName != null && selectedCount > 0) {
                Text(
                    text = "$selectedCount 个文件待处理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onPickFiles) {
                Icon(
                    imageVector = Icons.Outlined.AudioFile,
                    contentDescription = "重新选择文件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onPickFolder) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = "重新选择文件夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
