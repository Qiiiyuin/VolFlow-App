package com.example.volflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// 极简零依赖页面路由
enum class AppScreen { MainControl, AudioFileList }

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }

        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.MainControl) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.MainControl -> MainControlPanel(
                            modifier = Modifier.padding(innerPadding),
                            onNavigateToFiles = { currentScreen = AppScreen.AudioFileList }
                        )
                        AppScreen.AudioFileList -> AudioListScreen(
                            onBack = { currentScreen = AppScreen.MainControl }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainControlPanel(modifier: Modifier = Modifier, onNavigateToFiles: () -> Unit) {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // 主体中央控制台
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "VolFlow", fontSize = 36.sp, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = "当前内存占用： ${if(isServiceRunning) "25.2" else "0"} MB / 50 MB", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { if (isServiceRunning) 0.5f else 0f },
                modifier = Modifier.fillMaxWidth(0.85f).height(12.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = {
                    val intent = Intent(context, RecordingService::class.java).apply {
                        action = if (isServiceRunning) RecordingService.ACTION_STOP else RecordingService.ACTION_START
                    }
                    if (isServiceRunning) context.stopService(intent) else context.startForegroundService(intent)
                    isServiceRunning = !isServiceRunning
                },
                modifier = Modifier.fillMaxWidth(0.85f).height(56.dp)
            ) {
                Text(text = if (isServiceRunning) "停止后台录音" else "开始后台录音", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(20.dp))

            FilledTonalButton(
                onClick = { context.startService(Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_SAVE }) },
                modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
                enabled = isServiceRunning
            ) {
                Text(text = "保存过去5分钟回放", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(20.dp))

            // 新增：音频文件管理入口
            OutlinedButton(
                onClick = onNavigateToFiles,
                modifier = Modifier.fillMaxWidth(0.85f).height(56.dp)
            ) {
                Text(text = "音频文件管理", fontSize = 18.sp)
            }
        }

        // 右下角“支持”悬浮按钮
        TextButton(
            onClick = { showSupportDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Text(text = "支持", fontSize = 16.sp, color = MaterialTheme.colorScheme.outline)
        }

        // “支持”弹窗
        if (showSupportDialog) {
            AlertDialog(
                onDismissRequest = { showSupportDialog = false },
                title = { Text("关于支持") },
                text = {
                    Column {
                        Text("VolFlow是个开源项目。")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("GitHub: https://github.com/yourname/VolFlow", color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = { TextButton(onClick = { showSupportDialog = false }) { Text("确定") } }
            )
        }
    }
}