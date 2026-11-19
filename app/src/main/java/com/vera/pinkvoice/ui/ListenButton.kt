package com.vera.pinkvoice.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.VoiceEngine

/**
 * 麦克风权限 + 监听开关。
 *
 * 监测页和训练页都要能控制监听，所以抽出来共用 ——
 * 否则训练页就只能让用户切回监测页去开麦，很难用。
 *
 * @param compact 紧凑模式（训练页用）：矮一点、文字小一点
 */
@Composable
fun ListenButton(
    engine: VoiceEngine,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val status by engine.status.collectAsState()
    val running = status is com.vera.pinkvoice.audio.EngineStatus.Running

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    Button(
        onClick = {
            when {
                running -> engine.stop()
                granted -> engine.start()
                else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        modifier = modifier.height(if (compact) 42.dp else 54.dp),
        shape = RoundedCornerShape(if (compact) 13.dp else 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (running) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        ),
    ) {
        if (running) {
            StopGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            StatusDot(androidx.compose.ui.graphics.Color.White, if (compact) 8.dp else 10.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                running -> "停止监听"
                !granted -> "授予麦克风权限"
                else -> "开始监听"
            },
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
