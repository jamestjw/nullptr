package com.nullptr.trackpad.reactnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.common.LifecycleState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nullptr.trackpad.InputEvent
import com.nullptr.trackpad.MouseButton
import com.nullptr.trackpad.PairedComputer
import com.nullptr.trackpad.TailnetAddress
import com.nullptr.trackpad.TrackpadConnection

class TrackpadModule(private val context: ReactApplicationContext) :
    ReactContextBaseJavaModule(context), LifecycleEventListener {
    private val prefs = context.getSharedPreferences("trackpad", Context.MODE_PRIVATE)
    private val connection = TrackpadConnection { status ->
        if (context.hasActiveReactInstance()) context.emitDeviceEvent("trackpadStatus", status.name)
    }
    private var wantsConnection = false
    private var scanPromise: Promise? = null
    private val scanContract = ScanContract()
    private val scanner = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != SCAN_REQUEST) return
            val promise = scanPromise ?: return
            scanPromise = null
            val contents = scanContract.parseResult(resultCode, data).contents
            if (contents == null) {
                promise.resolve(null)
                return
            }
            val pairing = runCatching {
                val uri = Uri.parse(contents)
                require(uri.scheme == "nullptr" && uri.authority == "pair")
                val address = requireNotNull(TailnetAddress.parse(uri.getQueryParameter("host").orEmpty()))
                val code = uri.getQueryParameter("code").orEmpty()
                require(CODE.matches(code))
                Arguments.createMap().apply {
                    putString("address", address.toString())
                    putString("code", code)
                }
            }
            pairing.onSuccess { promise.resolve(it) }
                .onFailure { promise.reject("INVALID_QR", "This isn't a nullptr pairing code.") }
        }
    }

    override fun getName() = "Trackpad"

    override fun initialize() {
        context.addLifecycleEventListener(this)
        context.addActivityEventListener(scanner)
    }

    override fun invalidate() {
        context.removeLifecycleEventListener(this)
        context.removeActivityEventListener(scanner)
        UiThreadUtil.runOnUiThread {
            scanPromise = null
            connection.dispose()
        }
        super.invalidate()
    }

    @ReactMethod
    fun load(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putString("address", savedComputer()?.address?.toString())
            putInt("sensitivity", prefs.getInt("sensitivity", 30).coerceIn(0, 100))
            putBoolean("invertScroll", prefs.getBoolean("invertScroll", false))
        })
    }

    @ReactMethod
    fun pair(rawAddress: String, rawCode: String, promise: Promise) = UiThreadUtil.runOnUiThread {
        val address = TailnetAddress.parse(rawAddress)
        val code = rawCode.trim()
        if (address == null || !CODE.matches(code)) {
            promise.reject("INVALID_PAIRING", "Enter a Tailscale IPv4 address and the 12-character pairing code.")
            return@runOnUiThread
        }
        connection.pair(address, code) { result ->
            result.onSuccess { computer ->
                prefs.edit()
                    .putString("address", computer.address.toString())
                    .putString("token", computer.token)
                    .apply()
                promise.resolve(computer.address.toString())
            }.onFailure {
                promise.reject("PAIRING_FAILED", it.message ?: "Could not pair with the computer.")
            }
        }
    }

    @ReactMethod
    fun scan(promise: Promise) = UiThreadUtil.runOnUiThread {
        val activity = context.currentActivity
        if (activity == null || scanPromise != null) {
            promise.reject("SCAN_UNAVAILABLE", "Could not open the QR scanner.")
            return@runOnUiThread
        }
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the QR code printed by the companion")
            .setBeepEnabled(false)
        scanPromise = promise
        runCatching { activity.startActivityForResult(scanContract.createIntent(activity, options), SCAN_REQUEST) }
            .onFailure {
                scanPromise = null
                promise.reject("SCAN_UNAVAILABLE", "Could not open the QR scanner.")
            }
    }

    @ReactMethod
    fun setConnectionEnabled(enabled: Boolean) = UiThreadUtil.runOnUiThread {
        if (wantsConnection == enabled) return@runOnUiThread
        wantsConnection = enabled
        if (enabled && context.lifecycleState == LifecycleState.RESUMED) {
            onHostResume()
        } else if (!enabled) {
            connection.stop()
        }
    }

    @ReactMethod
    fun sendDelta(type: String, dx: Int, dy: Int) = UiThreadUtil.runOnUiThread {
        val event = when (type) {
            "move" -> InputEvent.Move(dx.coerceIn(-500, 500), dy.coerceIn(-500, 500))
            "scroll" -> InputEvent.Scroll(dx.coerceIn(-20, 20), dy.coerceIn(-20, 20))
            else -> return@runOnUiThread
        }
        connection.send(event)
    }

    @ReactMethod
    fun sendButton(button: String, down: Boolean) = UiThreadUtil.runOnUiThread {
        val mouseButton = when (button) {
            "left" -> MouseButton.LEFT
            "right" -> MouseButton.RIGHT
            else -> return@runOnUiThread
        }
        connection.send(InputEvent.Button(mouseButton, down))
    }

    @ReactMethod
    fun setSensitivity(value: Int) {
        prefs.edit().putInt("sensitivity", value.coerceIn(0, 100)).apply()
    }

    @ReactMethod
    fun setInvertScroll(value: Boolean) {
        prefs.edit().putBoolean("invertScroll", value).apply()
    }

    override fun onHostResume() {
        if (wantsConnection) savedComputer()?.let(connection::start)
    }

    override fun onHostPause() = connection.stop()

    override fun onHostDestroy() {
        onHostPause()
        scanPromise?.resolve(null)
        scanPromise = null
    }

    private fun savedComputer(): PairedComputer? {
        val address = TailnetAddress.parse(prefs.getString("address", "").orEmpty()) ?: return null
        val token = prefs.getString("token", "").orEmpty()
        return token.takeIf { it.isNotBlank() }?.let { PairedComputer(address, it) }
    }

    private companion object {
        const val SCAN_REQUEST = 4782
        val CODE = Regex("[0-9a-fA-F]{12}")
    }
}
