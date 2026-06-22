package com.example.volflow

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var fileList by remember { mutableStateOf<List<File>>(emptyList()) }
    var currentPage by remember { mutableStateOf(0) }
    val itemsPerPage = 5

    // 实时扫描系统 /Music/VolFlow 目录下的 wav 文件
    LaunchedEffect(Unit) {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VolFlow")
        if (folder.exists() && folder.isDirectory) {
            fileList = folder.listFiles { file -> file.extension == "wav" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }

    val totalPages = if (fileList.isEmpty()) 1 else (fileList.size + itemsPerPage - 1) / itemsPerPage
    val currentDisplayFiles = fileList.drop(currentPage * itemsPerPage).take(itemsPerPage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音频文件管理 (${fileList.size})") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← 返回", fontSize = 16.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        bottomBar = {
            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { currentPage-- }, enabled = currentPage > 0) { Text("上一页") }
                    Text("第 ${currentPage + 1} / $totalPages 页", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { currentPage++ }, enabled = currentPage < totalPages - 1) { Text("下一页") }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            if (fileList.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无录音文件\n请先录制并保存一段回放", color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                items(currentDisplayFiles, key = { it.absolutePath }) { file ->
                    AudioItemCard(file = file, context = context)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun AudioItemCard(file: File, context: Context) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(1f) }

    var showMarkDialog by remember { mutableStateOf(false) }
    var showFmConfirmDialog by remember { mutableStateOf(false) }
    var editingMarkerTimeMs by remember { mutableStateOf(0) }
    var customMarkerName by remember { mutableStateOf("") }

    // 【核心修复：标记点持续可用】将伴生标记文件移入内部合规沙盒路径，规避公共目录写保护限制
    val sidecarFile = remember(file) { File(context.filesDir, file.name + ".mrk") }
    var markers by remember(file) {
        mutableStateOf<List<Pair<Int, String>>>(readSidecarMarkers(sidecarFile))
    }

    val syncSidecarFunc = { newList: List<Pair<Int, String>> ->
        writeSidecarMarkers(sidecarFile, newList)
    }

    // 时间格式化辅助工具 (将毫秒数转换为 01:23 样式)
    val formatTimeFunc = { ms: Float ->
        val totalSeconds = (ms / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            progress = mediaPlayer?.currentPosition?.toFloat() ?: 0f
            delay(100)
        }
    }

    DisposableEffect(file) {
        onDispose { mediaPlayer?.release() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = file.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = {
                    if (mediaPlayer == null) {
                        mediaPlayer = MediaPlayer.create(context, Uri.fromFile(file)).apply {
                            setOnCompletionListener { isPlaying = false; progress = 0f }
                        }
                        duration = mediaPlayer?.duration?.toFloat() ?: 1f
                    }
                    if (isPlaying) mediaPlayer?.pause() else mediaPlayer?.start()
                    isPlaying = !isPlaying
                }) {
                    Text(if (isPlaying) "暂停" else "播放")
                }

                FilledTonalButton(
                    onClick = {
                        mediaPlayer?.pause()
                        isPlaying = false
                        editingMarkerTimeMs = mediaPlayer?.currentPosition ?: 0
                        customMarkerName = "标点 (${editingMarkerTimeMs / 1000}秒)"
                        showMarkDialog = true
                    },
                    enabled = mediaPlayer != null
                ) { Text("标记") }

                OutlinedButton(onClick = { showFmConfirmDialog = true }) {
                    Text("文件管理中打开")
                }
            }

            if (mediaPlayer != null) {
                Spacer(modifier = Modifier.height(8.dp))

                // 进度条主体
                Slider(
                    value = progress,
                    onValueChange = { valMs ->
                        progress = valMs
                        mediaPlayer?.seekTo(valMs.toInt())
                    },
                    valueRange = 0f..duration,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                )

                // 【核心新增：明确的时间进度文字】
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = formatTimeFunc(progress), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = formatTimeFunc(duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (markers.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
                        Text("📌 标记点轨道:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))

                        markers.forEach { (timeMs, name) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable {
                                        editingMarkerTimeMs = timeMs
                                        customMarkerName = name
                                        showMarkDialog = true
                                    }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = name, fontSize = 13.sp)
                                    Text(text = " (${formatTimeFunc(timeMs.toFloat())})", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                }

                                // 【核心功能：一键跳转并就绪】
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable {
                                        mediaPlayer?.seekTo(timeMs)
                                        progress = timeMs.toFloat()
                                    }
                                ) {
                                    Text(text = "➦ 跳至", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMarkDialog) {
        AlertDialog(
            onDismissRequest = { showMarkDialog = false },
            title = { Text("标记点设置") },
            text = {
                OutlinedTextField(
                    value = customMarkerName,
                    onValueChange = { customMarkerName = it },
                    label = { Text("标记名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (customMarkerName.isNotBlank()) {
                        val updated = (markers.filterNot { it.first == editingMarkerTimeMs } + Pair(editingMarkerTimeMs, customMarkerName)).sortedBy { it.first }
                        markers = updated
                        syncSidecarFunc(updated)
                    }
                    showMarkDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                if (markers.any { it.first == editingMarkerTimeMs }) {
                    TextButton(onClick = {
                        val updated = markers.filterNot { it.first == editingMarkerTimeMs }
                        markers = updated
                        syncSidecarFunc(updated)
                        showMarkDialog = false
                    }) { Text("删除标记", color = MaterialTheme.colorScheme.error) }
                } else {
                    OutlinedButton(onClick = { showMarkDialog = false }) { Text("取消") }
                }
            }
        )
    }

    if (showFmConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showFmConfirmDialog = false },
            title = { Text("打开文件管理") },
            text = { Text("是否跳转系统原生文件管理器查看音频目录？") },
            confirmButton = {
                Button(onClick = {
                    showFmConfirmDialog = false
                    // 适配 vivo 等厂商对文件夹路径的严格隔离：通过隐式系统挂载层直接突入 Music 根目录
                    try {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Music")
                            setDataAndType(uri, "*/*")
                            addCategory(Intent.CATEGORY_OPENABLE)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "请手动前往手机自带的【文件管理 -> 音乐 -> VolFlow】", Toast.LENGTH_LONG).show()
                    }
                }) { Text("确定跳转") }
            },
            dismissButton = { OutlinedButton(onClick = { showFmConfirmDialog = false }) { Text("取消") } }
        )
    }
}

// 外部数据流持久化辅助函数
private fun readSidecarMarkers(sidecarFile: File): List<Pair<Int, String>> {
    return try {
        if (sidecarFile.exists()) {
            sidecarFile.readText().split("\n").mapNotNull { line ->
                val p = line.split("::")
                if (p.size == 2) {
                    val time = p[0].toIntOrNull()
                    if (time != null) Pair(time, p[1]) else null
                } else null
            }
        } else emptyList()
    } catch (e: Exception) { emptyList() }
}

private fun writeSidecarMarkers(sidecarFile: File, list: List<Pair<Int, String>>) {
    try {
        if (list.isEmpty()) {
            if (sidecarFile.exists()) sidecarFile.delete()
        } else {
            sidecarFile.writeText(list.joinToString("\n") { "${it.first}::${it.second}" })
        }
    } catch (e: Exception) { e.printStackTrace() }
}