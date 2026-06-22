package com.example.volflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {

    private var audioManager: AudioBufferManager? = null

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SAVE = "ACTION_SAVE"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = AudioBufferManager()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_content))
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now) // 系统自带麦克风小图标
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()

                // 兼容 Android 14 强制要求的 microphone 前台服务类型校验
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(1, notification)
                }

                audioManager?.startRecording()
                showToast(getString(R.string.btn_start_recording) + " 服务已运行")
            }
            ACTION_STOP -> {
                audioManager?.stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                showToast("录音服务已终止")
            }
            ACTION_SAVE -> {
                // 1. 严格锁定命名规范为：Replay_2026-06-22_13-50-09.wav
                val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
                val niceFileName = "Replay_$timeFormat.wav"

                val tempFile = File(cacheDir, niceFileName)
                val success = audioManager?.saveToWavFile(tempFile) ?: false

                if (success) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, niceFileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                        // 显式锁定公共音乐目录下的子文件夹
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC + "/VolFlow")
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1) // 锁定状态，防止系统干扰命名
                    }

                    val uri = contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { targetUri ->
                        contentResolver.openOutputStream(targetUri)?.use { outStream ->
                            tempFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        // 解除锁定，向全系统广播文件生成完毕
                        values.clear()
                        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(targetUri, values, null, null)
                    }
                    showToast("已保存音频: $niceFileName")
                } else {
                    showToast("保存失败：当前内存缓冲区尚无数据")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        audioManager?.stopRecording()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }
}