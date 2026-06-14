package com.nexora.hammerscale

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * Simple Security Module for HammerScale
 */
object SecurityModule {

    private const val TAG = "Security"
    
    private val ROOT_PACKAGES = listOf("com.topjohnwu.magisk", "com.kingroot.master")
    private val FRIDA_PORTS = listOf(27042, 27043)
    private val EMULATOR_FILES = listOf("/system/bin/qemu-props", "/dev/qemu_pipe")
    private val EMULATOR_PACKAGES = listOf("com.bluestacks", "com.ldy.ldplayer", "com.nox.vm", "com.memu")

    fun isDeviceCompromised(context: Context): Boolean {
        return checkRoot() || checkFrida() || checkEmulator()
    }

    fun getDetectedThreats(context: Context): List<String> {
        val threats = mutableListOf<String>()
        if (checkRoot()) threats.add("Root Detected")
        if (checkFrida()) threats.add("Frida Detected")
        if (checkEmulator()) threats.add("Emulator Detected")
        return threats
    }

    private fun checkRoot(): Boolean {
        val pm = try { App.instance.packageManager } catch (e: Exception) { return false }
        ROOT_PACKAGES.forEach { pkg ->
            try { pm.getPackageInfo(pkg, 0); Log.d(TAG, "Root: $pkg"); return true } catch (e: Exception) { }
        }
        listOf("/system/xbin/su", "/system/bin/su").forEach { if (File(it).exists()) { Log.d(TAG, "su: $it"); return true } }
        return false
    }

    private fun checkFrida(): Boolean {
        try {
            val content = File("/proc/net/tcp").readText()
            FRIDA_PORTS.forEach { port ->
                if (content.contains(String.format("%04X", port))) { Log.d(TAG, "Frida port: $port"); return true }
            }
        } catch (e: Exception) { }
        listOf("/data/local/tmp/frida-server").forEach { if (File(it).exists()) { Log.d(TAG, "Frida file: $it"); return true } }
        return false
    }

    private fun checkEmulator(): Boolean {
        EMULATOR_FILES.forEach { if (File(it).exists()) { Log.d(TAG, "Emulator file: $it"); return true } }
        val pm = try { App.instance.packageManager } catch (e: Exception) { return false }
        EMULATOR_PACKAGES.forEach { pkg ->
            try { pm.getPackageInfo(pkg, 0); Log.d(TAG, "Emulator pkg: $pkg"); return true } catch (e: Exception) { }
        }
        return false
    }
}
