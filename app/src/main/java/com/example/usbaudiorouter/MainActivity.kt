package com.example.usbaudiorouter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.usbaudiorouter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Track whether we programmatically changed the toggle to avoid re-entrancy
    private var suppressToggleCallback = false

    // ── Receivers ─────────────────────────────────────────────────────────────

    /** Receives routing state updates from AudioRouterService via LocalBroadcast. */
    private val routingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isRouting = intent.getBooleanExtra(AudioRouterService.EXTRA_IS_ROUTING, false)
            val message   = intent.getStringExtra(AudioRouterService.EXTRA_STATUS_MESSAGE) ?: ""
            applyRoutingState(isRouting, message)
        }
    }

    /** Listens for USB attach/detach events from the system. */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshUsbStatus()
        }
    }

    /** More reliable detection for audio-specific devices. */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // Give the system a moment to fully register the new hardware
            binding.root.postDelayed({ refreshUsbStatus() }, 500)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshUsbStatus()
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────────

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                sendServiceCommand(AudioRouterService.ACTION_START)
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required to read USB audio input",
                    Toast.LENGTH_LONG
                ).show()
                setToggle(false)
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToggle()
        registerReceivers()
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshUsbStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshUsbStatus()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(routingStatusReceiver)
        unregisterReceiver(usbReceiver)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Nothing to do but prevent recreation
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupToggle() {
        binding.toggleRoute.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggleCallback) return@setOnCheckedChangeListener
            if (isChecked) onRoutingRequested() else onRoutingCancelled()
        }
    }

    private fun onRoutingRequested() {
        if (!UsbAudioHelper.isUsbAudioConnected(this)) {
            Toast.makeText(this, "No USB audio device connected", Toast.LENGTH_SHORT).show()
            setToggle(false)
            return
        }
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> sendServiceCommand(AudioRouterService.ACTION_START)
            else -> requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onRoutingCancelled() {
        sendServiceCommand(AudioRouterService.ACTION_STOP)
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun applyRoutingState(isRouting: Boolean, message: String) {
        setToggle(isRouting)
        binding.routingStatusText.text = if (isRouting) {
            "Active — audio playing through system output"
        } else {
            "Inactive"
        }
        if (!isRouting && message.isNotEmpty() &&
            message != "Routing stopped" /* suppress normal stop noise */) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUsbStatus(isRetry: Boolean = false) {
        val connected  = UsbAudioHelper.isUsbAudioConnected(this)
        val deviceName = UsbAudioHelper.getUsbAudioDeviceName(this)

        binding.usbIndicator.isActivated = connected
        binding.usbStatusText.text = if (connected) "$deviceName connected" else "No USB audio device detected"
        binding.toggleRoute.isEnabled = connected

        if (!connected) {
            setToggle(false)
            // If we just got an attach event but don't see the device yet, 
            // the audio stack might still be initializing. Try once more in 1s.
            if (!isRetry) {
                binding.root.postDelayed({ refreshUsbStatus(true) }, 1000)
            }
        }
    }

    /** Safely set the toggle without triggering the listener. */
    private fun setToggle(checked: Boolean) {
        suppressToggleCallback = true
        binding.toggleRoute.isChecked = checked
        suppressToggleCallback = false
    }

    // ── Service commands ──────────────────────────────────────────────────────

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, AudioRouterService::class.java).apply { this.action = action }
        if (action == AudioRouterService.ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ── Receiver registration ─────────────────────────────────────────────────

    private fun registerReceivers() {
        // Internal broadcast from AudioRouterService
        val routingFilter = IntentFilter(AudioRouterService.ACTION_ROUTING_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(routingStatusReceiver, routingFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(routingStatusReceiver, routingFilter)
        }

        // System broadcasts for USB plug/unplug
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, usbFilter)
    }
}
