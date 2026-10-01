package com.example.usbaudiorouter

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

object UsbAudioHelper {

    /**
     * The Saramonic SR-C2003 reports:
     *   type        = AudioDeviceInfo.TYPE_USB_DEVICE
     *   productName = "USB PnP Audio Device"  (Android strips the "USB-Audio - " kernel prefix)
     *   sampleRates = [48000]
     *   channelCounts = [2]
     */
    private const val SR_C2003_PRODUCT_NAME = "USB PnP Audio Device"
    const val SAMPLE_RATE = 48_000

    /**
     * Finds the SR-C2003 (or any USB_DEVICE input whose name contains the known
     * product name substring). Falls back to the first TYPE_USB_DEVICE if the
     * exact name is not matched, so the app still works if Saramonic ever changes
     * the descriptor string.
     */
    fun preferredInputDevice(context: Context): AudioDeviceInfo? {
        val usbInputs = allUsbAudioInputs(context)
        return usbInputs.firstOrNull { it.productName?.contains(SR_C2003_PRODUCT_NAME) == true }
            ?: usbInputs.firstOrNull()
    }

    /** True when the SR-C2003 (or any USB audio input) is connected. */
    fun isUsbAudioConnected(context: Context): Boolean = allUsbAudioInputs(context).isNotEmpty()

    /** Display name for the UI — uses the device's reported product name. */
    fun getUsbAudioDeviceName(context: Context): String {
        val name = preferredInputDevice(context)?.productName?.toString()
        return if (!name.isNullOrBlank()) name else "USB Audio Device"
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun allUsbAudioInputs(context: Context): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // TYPE_USB_DEVICE  = standard UAC device (SR-C2003)
        // TYPE_USB_HEADSET = USB headset with integrated mic
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }
}
