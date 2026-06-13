package com.nexora.hammerscale

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.nexora.hammerscale.databinding.ActivityMainBinding
import com.nexora.hammerscale.model.ConnectionViewModel
import com.nexora.hammerscale.model.ConnectionViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ConnectionViewModel

    private val VPN_REQUEST_CODE     = 100
    private val OVERLAY_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, ConnectionViewModelFactory())[ConnectionViewModel::class.java]

        // Play/Pause button
        binding.btnPlay.setOnClickListener {
            if (viewModel.vpnRunning.value == true) {
                // Stop VPN and overlay (don't launch app)
                stopVpn()
            } else {
                // Start VPN and launch game
                requestVpnPermission()
            }
        }

        // Discord link
        binding.tvDiscordLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/AW9vGhVA2j")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        // Observe VPN state to update button icon
        viewModel.vpnRunning.observe(this) { running ->
            binding.btnPlay.setImageResource(
                if (running) android.R.drawable.ic_media_pause
                else R.drawable.ic_play
            )
        }

        // Request overlay permission on first launch so it's ready when needed
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────

    private fun startOverlay() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        })
        // After overlay starts, launch the game
        Handler(Looper.getMainLooper()).postDelayed({
            launchTargetApp()
        }, 500)
    }

    private fun stopOverlay() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_REQUEST_CODE)
    }

    private fun launchTargetApp() {
        val intent = packageManager.getLaunchIntentForPackage(TrafficVpnService.TARGET_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    // ── VPN ───────────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
        else startVpn()
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_REQUEST_CODE -> if (resultCode == Activity.RESULT_OK) startVpn()
            OVERLAY_REQUEST_CODE -> {
                // Permission result — overlay will start automatically when VPN starts
            }
        }
    }

    private fun startVpn() {
        startService(Intent(this, TrafficVpnService::class.java).apply {
            action = TrafficVpnService.ACTION_START
        })
        // Start overlay, then launch game
        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).postDelayed({
                startOverlay()
            }, 300)
        }
    }

    private fun stopVpn() {
        // Stop overlay first
        stopOverlay()
        // Then stop VPN
        startService(Intent(this, TrafficVpnService::class.java).apply {
            action = TrafficVpnService.ACTION_STOP
        })
    }
}
