package com.kirtan.companion.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo as PlatformDevice
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kirtan.companion.R
import com.kirtan.companion.Strings

/**
 * Where the drum is coming out: the phone's speaker, wired headphones, or a
 * particular Bluetooth speaker.
 *
 * It matters because the display delay is a property of the OUTPUT, not of the
 * phone. A JBL speaker might need +120 ms, earbuds +40 ms and the phone speaker
 * nothing, so the delay is stored per [key], and swapping speakers mid-kirtan
 * swaps the delay with it.
 */
data class OutputRoute(
    /** Stable per device: its kind plus its product name, e.g. `bt:JBL Flip 5`. */
    val key: String,
    /** The device's own product name, or "" for outputs we name ourselves. */
    val name: String,
    val bluetooth: Boolean,
) {
    /**
     * What to call it on screen: "JBL Flip 5", "Phone speaker". Resolved on each
     * read, not stored: the route outlives a language change (it sits in the
     * ViewModel across the activity recreate), and a stored "Altavoz del
     * teléfono" would linger after switching back to English.
     */
    val label: String
        get() = when {
            name.isNotEmpty() -> name
            key == "speaker" -> Strings.get(R.string.route_speaker)
            key == "wired" -> Strings.get(R.string.route_wired)
            bluetooth -> Strings.get(R.string.route_bluetooth)
            else -> Strings.get(R.string.route_external)
        }

    companion object {
        val SPEAKER = OutputRoute("speaker", "", bluetooth = false)
    }
}

/**
 * Watches the media output and publishes it as a [StateFlow].
 *
 * The platform calls back when a device connects or disconnects; on each call
 * the route is worked out again rather than tracked incrementally, because
 * "which device is media actually going to" is the platform's decision and
 * asking it is simpler than modelling its rules.
 */
class OutputRouteWatcher(context: Context) {

    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _route = MutableStateFlow(current())
    val route: StateFlow<OutputRoute> = _route.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out PlatformDevice>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out PlatformDevice>) = refresh()
    }

    init {
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** Work the route out again. Also called on Play, in case a switch went unannounced. */
    fun refresh() {
        _route.value = current()
    }

    private fun current(): OutputRoute {
        val device = mediaDevice() ?: return OutputRoute.SPEAKER
        return routeOf(device)
    }

    /**
     * The device media plays through. Android 13 can say so directly; before
     * that, pick the way the platform's own routing does: Bluetooth over wired
     * over the built-in speaker.
     */
    private fun mediaDevice(): PlatformDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            manager.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { info ->
                // Map the routing answer back to the full device, which carries
                // the product name.
                return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == info.type && it.address == info.address }
                    ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == info.type }
            }
        }
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { isBluetooth(it.type) }
            ?: outputs.firstOrNull { isWired(it.type) }
            ?: outputs.firstOrNull { it.type == PlatformDevice.TYPE_BUILTIN_SPEAKER }
    }

    private fun routeOf(device: PlatformDevice): OutputRoute {
        val name = device.productName?.toString()?.trim().orEmpty()
        return when {
            isBluetooth(device.type) -> OutputRoute(
                key = "bt:${name.ifEmpty { "unknown" }}",
                name = name,
                bluetooth = true,
            )
            isWired(device.type) -> OutputRoute("wired", "", bluetooth = false)
            device.type == PlatformDevice.TYPE_BUILTIN_SPEAKER -> OutputRoute.SPEAKER
            else -> OutputRoute(
                key = "other:${device.type}:$name",
                name = name,
                bluetooth = false,
            )
        }
    }

    private fun isBluetooth(type: Int): Boolean = type == PlatformDevice.TYPE_BLUETOOTH_A2DP ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == PlatformDevice.TYPE_BLE_HEADSET || type == PlatformDevice.TYPE_BLE_SPEAKER)) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == PlatformDevice.TYPE_BLE_BROADCAST)

    private fun isWired(type: Int): Boolean = type == PlatformDevice.TYPE_WIRED_HEADPHONES ||
        type == PlatformDevice.TYPE_WIRED_HEADSET ||
        type == PlatformDevice.TYPE_USB_HEADSET ||
        type == PlatformDevice.TYPE_USB_DEVICE
}
