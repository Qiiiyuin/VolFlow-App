package com.example.volflow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AudioBufferManager {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    // 1. 定义音频标准（44.1kHz CD级采样率, 单声道, 16位PCM编码）
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 2. 录满5分钟声音，内存池需要开多大？
    // 算法：44100(采样率) * 1(单声道) * 2(16位=2字节) * 300秒 = 26,460,000 字节 (约25.2MB)
    private val bufferSizeInBytes = 26460000
    private val circularBuffer = ByteArray(bufferSizeInBytes) // 我们的常驻内存池

    private var writeIndex = 0 // 内存池当前的写入指针
    private var totalBytesWritten = 0L // 开机以来吞噬的总字节数

    @SuppressLint("MissingPermission") // 录音权限留在阶段四动态申请，此处压制警告
    fun startRecording() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 2
        )

        audioRecord?.startRecording()
        isRecording = true

        // 开启后台死循环线程，往内存池疯狂注水
        Thread {
            val tempBuffer = ByteArray(minBufferSize)
            while (isRecording) {
                val readSize = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
                if (readSize > 0) {
                    for (i in 0 until readSize) {
                        circularBuffer[writeIndex] = tempBuffer[i]
                        // 【算法灵魂】指针一旦顶到25MB的尽头，自动归零从头覆盖老数据！
                        writeIndex = (writeIndex + 1) % bufferSizeInBytes
                        totalBytesWritten++
                    }
                }
            }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // 核心绝技：把内存流瞬间打包成手机可播放的 .wav 音频文件
    fun saveToWavFile(outputFile: File): Boolean {
        if (totalBytesWritten == 0L) return false

        return try {
            FileOutputStream(outputFile).use { fos ->
                val validDataLength = if (totalBytesWritten < bufferSizeInBytes) {
                    totalBytesWritten.toInt() // 还没录满一圈
                } else {
                    bufferSizeInBytes // 已经套圈了，取满5分钟
                }

                // 写入44字节的标准WAV文件头
                writeWavHeader(fos, validDataLength.toLong(), sampleRate.toLong(), 1, (sampleRate * 2).toLong())

                if (totalBytesWritten < bufferSizeInBytes) {
                    fos.write(circularBuffer, 0, writeIndex)
                } else {
                    // 已套圈：先抄写后半段（最老的声音），再抄写前半段（最新的声音）
                    fos.write(circularBuffer, writeIndex, bufferSizeInBytes - writeIndex)
                    fos.write(circularBuffer, 0, writeIndex)
                }
            }
            true
        } catch (e: IOException) {
            Log.e("AudioBufferManager", "写入WAV失败", e)
            false
        }
    }

    private fun writeWavHeader(fos: FileOutputStream, totalAudioLen: Long, sampleRate: Long, channels: Int, byteRate: Long) {
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte(); header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        fos.write(header, 0, 44)
    }
}