package com.nexora.hammerscale

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.LiveMessage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogDownloader {

    fun downloadAndShare(context: Context, connections: List<ConnectionEntry>) {
        val totalMessages = connections.sumOf { synchronized(it.messages) { it.messages.size } }
        if (totalMessages == 0) {
            Toast.makeText(context, "No packets captured yet", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val logsDir = File(context.cacheDir, "logs").also { it.mkdirs() }
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipFile = File(logsDir, "packetcap_$dateStr.zip")

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                for (conn in connections) {
                    val msgs = synchronized(conn.messages) { conn.messages.toList() }
                    if (msgs.isEmpty()) continue

                    val connLabel = sanitizeFolderName("${conn.displayAddress}_${conn.protocol}")

                    zos.putNextEntry(ZipEntry("$connLabel/info.txt"))
                    val info = buildString {
                        appendLine("Connection: ${conn.id}")
                        appendLine("Protocol: ${conn.protocol}")
                        appendLine("Source Port: ${conn.srcPort}")
                        appendLine("Destination: ${conn.displayAddress}")
                        appendLine("Status: ${conn.status}")
                        appendLine("Start Time: ${conn.startTimeStr}")
                        appendLine("Bytes Out: ${conn.bytesOut}")
                        appendLine("Bytes In: ${conn.bytesIn}")
                        appendLine("Total Packets: ${msgs.size}")
                    }
                    zos.write(info.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    val timeFmt = SimpleDateFormat("HHmmssSSS", Locale.getDefault())
                    msgs.sortedBy { it.timestamp }.forEachIndexed { idx, msg ->
                        val dir = if (msg.direction == LiveMessage.Direction.OUTBOUND) "outbound" else "inbound"
                        val timeStr = timeFmt.format(Date(msg.timestamp))
                        val filename = "$connLabel/$dir/${timeStr}_${idx}.bin"
                        zos.putNextEntry(ZipEntry(filename))
                        zos.write(msg.data)
                        zos.closeEntry()
                    }
                }
            }

            val saved = saveToDownloads(context, zipFile, "packetcap_$dateStr.zip")
            if (saved) {
                Toast.makeText(context, "Saved to Downloads/packetcap_$dateStr.zip", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to save - check permissions", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToDownloads(context: Context, file: File, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return file.copyTo(File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        fileName
                    ))
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                file.copyTo(File(downloadsDir, fileName), overwrite = true)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sanitizeFolderName(name: String): String {
        return name.replace(Regex("""[<>:"/\\|?*]"""), "_").take(128)
    }
}
