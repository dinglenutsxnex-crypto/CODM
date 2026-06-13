package com.nexora.hammerscale

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nexora.hammerscale.model.LiveMessage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogDownloader {

    /**
     * Naming convention with command names for AI readability:
     *   - Group consecutive messages by direction
     *   - Each group gets a sequential index per direction (user_ping_1, user_ping_2 / server_login_1, ...)
     *   - Command name extracted from message or "unknown" if unclassified
     *   - If a group has multiple packets: user_ping_2.1.bin, user_ping_2.2.bin …
     *   - If a group has one packet:      user_ping_1.bin (no decimal)
     */
    fun downloadAndShare(context: Context, messages: List<LiveMessage>) {
        if (messages.isEmpty()) {
            Toast.makeText(context, "No messages to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // ── Group consecutive messages by direction ────────────────
            data class Group(val dir: LiveMessage.Direction, val packets: MutableList<Pair<ByteArray, String>>)
            val groups = mutableListOf<Group>()
            for (msg in messages) {
                val cmdTag = msg.filenameTag
                if (groups.isEmpty() || groups.last().dir != msg.direction) {
                    groups.add(Group(msg.direction, mutableListOf(msg.data.clone() to cmdTag)))
                } else {
                    groups.last().packets.add(msg.data.clone() to cmdTag)
                }
            }

            // ── Build zip ─────────────────────────────────────────────
            val logsDir = File(context.cacheDir, "logs").also { it.mkdirs() }
            val ts = System.currentTimeMillis()
            val zipFile = File(logsDir, "hammerscale_$ts.zip")

            var userIdx   = 0
            var serverIdx = 0

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                for (group in groups) {
                    val dirName = if (group.dir == LiveMessage.Direction.OUTBOUND) "user" else "server"
                    val n = if (group.dir == LiveMessage.Direction.OUTBOUND) ++userIdx else ++serverIdx

                    if (group.packets.size == 1) {
                        val (_, cmdTag) = group.packets[0]
                        zos.putNextEntry(ZipEntry("${dirName}_${cmdTag}_${n}.bin"))
                        zos.write(group.packets[0].first)
                        zos.closeEntry()
                    } else {
                        group.packets.forEachIndexed { i, (data, cmdTag) ->
                            zos.putNextEntry(ZipEntry("${dirName}_${cmdTag}_${n}.${i + 1}.bin"))
                            zos.write(data)
                            zos.closeEntry()
                        }
                    }
                }
            }

            // ── Share via FileProvider ────────────────────────────────
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                zipFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "HAMMERSCALE logs")
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
}
