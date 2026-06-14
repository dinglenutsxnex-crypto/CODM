package com.nexora.hammerscale

import android.os.Bundle
import android.widget.Toast
import android.util.Log

class App : android.app.Application() {

    companion object {
        private const val TAG = "HammerScale"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        if (checkSecurity()) {
            Log.d(TAG, "App started normally")
        }
        // App continues - if blocked, handleBlock() kills it
    }

    private fun checkSecurity(): Boolean {
        try {
            val threats = SecurityModule.getDetectedThreats(this)
            if (threats.isNotEmpty()) {
                val msg = "Blocked: ${threats.joinToString(", ")}"
                Log.e(TAG, msg)
                
                // Show toast on main thread
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                
                // Kill app after delay
                android.os.Handler(mainLooper).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 3000)
                
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Security check error: ${e.message}")
        }
        return true
    }
}
