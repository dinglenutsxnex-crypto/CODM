package com.nexora.hammerscale

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.LiveMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogDownloader {

    fun downloadAndShare(context: Context, connections: List<ConnectionEntry>) {
        val totalMessages = connections.sumOf { synchronized(it.messages) { it.messages.size } }
        if (totalMessages == 0) {
            Toast.makeText(context, "No messages to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val logsDir = File(context.cacheDir, "logs").also { it.mkdirs() }
            val ts = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(ts))
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

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                zipFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Packet Capture Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Save logs").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sanitizeFolderName(name: String): String {
        return name.replace(Regex("""[<>:"/\\|?*]"""), "_").take(128)
    }
}
