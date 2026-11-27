package com.nullptr.trackpad

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private enum class Screen { PAIRING, TRACKPAD, SETTINGS }

    private val backgroundColor = Color.rgb(17, 22, 28)
    private val accent = Color.rgb(168, 233, 202)
    private val textColor = Color.rgb(236, 243, 249)
    private val muted = Color.rgb(170, 183, 195)
    private val prefs by lazy { getSharedPreferences("trackpad", Context.MODE_PRIVATE) }
    private lateinit var connection: TrackpadConnection
    private lateinit var root: LinearLayout
    private lateinit var pad: TouchpadView
    private lateinit var status: TextView
    private lateinit var addressField: EditText
    private lateinit var codeField: EditText
    private lateinit var pairStatus: TextView
    private lateinit var submit: Button
    private lateinit var scan: Button
    private var cancel: Button? = null
    private var paired: PairedComputer? = null
    private var screen = Screen.PAIRING
    private var foregroundActivity = false
    private val settingsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showTrackpad()
    }
    private val scanPairing = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::readPairingQr)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        connection = TrackpadConnection(::renderConnection)
        onBackPressedDispatcher.addCallback(this, settingsBack)
        val address = TailnetAddress.parse(prefs.getString("address", "").orEmpty())
        val token = prefs.getString("token", "").orEmpty()
        if (address != null && token.isNotBlank()) paired = PairedComputer(address, token)
        val savedScreen = savedInstanceState?.getString("screen")
        if (paired == null || savedScreen == Screen.PAIRING.name) {
            showPairing()
            addressField.setText(savedInstanceState?.getString("address") ?: paired?.address?.toString())
        } else if (savedScreen == Screen.SETTINGS.name) {
            showSettings()
        } else {
            showTrackpad()
        }
    }

    override fun onStart() {
        super.onStart()
        foregroundActivity = true
        if (screen != Screen.PAIRING) paired?.let(connection::start)
    }

    override fun onStop() {
        foregroundActivity = false
        connection.stop()
        super.onStop()
    }

    override fun onDestroy() {
        connection.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        if (screen == Screen.PAIRING) outState.putString("address", addressField.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun readPairingQr(contents: String) {
        val uri = Uri.parse(contents)
        val address = if (uri.scheme == "nullptr" && uri.authority == "pair") {
            TailnetAddress.parse(uri.getQueryParameter("host").orEmpty())
        } else {
            null
        }
        val code = if (address != null) uri.getQueryParameter("code").orEmpty() else ""
        if (address == null || !validCode(code)) {
            setPairStatus("This isn't a nullptr pairing code.", error = true)
            return
        }
        addressField.setText(address.toString())
        codeField.setText(code)
        pair()
    }

    private fun showPairing() {
        screen = Screen.PAIRING
        connection.stop()
        buildRoot()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        scroll.addView(form)
        form.addView(label("NULLPTR", 12, accent, bold = true))
        form.addView(label("Pair a computer", 32, textColor, bold = true), spaced(top = 10))
        form.addView(label("Run the companion on your computer with Tailscale connected.", 16, muted), spaced(top = 10))
        scan = button("Scan pairing QR") {
            scanPairing.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR code printed by the companion")
                    .setBeepEnabled(false),
            )
        }
        form.addView(scan, spaced(top = 18))
        form.addView(label("Or enter the details manually", 15, muted), spaced(top = 16))
        addressField = EditText(this).apply {
            hint = "100.x.x.x:47822"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(label("Tailscale address", 14, textColor), spaced(top = 14))
        form.addView(addressField)
        codeField = EditText(this).apply {
            hint = "12 characters"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        form.addView(label("Pairing code", 14, textColor), spaced(top = 12))
        form.addView(codeField)
        pairStatus = label("", 14, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        form.addView(pairStatus, spaced(top = 8))
        submit = button("Pair computer", prominent = true, action = ::pair)
        form.addView(submit, spaced(top = 8))
        cancel = paired?.let {
            button("Back to trackpad") { resumeTrackpad() }.also { form.addView(it, spaced(top = 8)) }
        }
        form.requestFocus()
    }

    private fun pair() {
        val address = TailnetAddress.parse(addressField.text.toString())
        val code = codeField.text.toString().trim()
        if (address == null || !validCode(code)) {
            setPairStatus("Enter a Tailscale IPv4 address and the 12-character pairing code.", error = true)
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        setPairStatus("Pairing with $address…")
        setPairingEnabled(false)
        connection.pair(address, code) { result ->
            setPairingEnabled(true)
            result.onSuccess { computer ->
                prefs.edit().putString("address", computer.address.toString()).putString("token", computer.token).apply()
                paired = computer
                resumeTrackpad()
            }.onFailure {
                setPairStatus(it.message ?: "Could not pair with the computer.", error = true)
            }
        }
    }

    private fun validCode(code: String) = code.matches(Regex("[0-9a-fA-F]{12}"))

    private fun setPairingEnabled(enabled: Boolean) {
        submit.isEnabled = enabled
        scan.isEnabled = enabled
        cancel?.isEnabled = enabled
    }

    private fun setPairStatus(message: String, error: Boolean = false) {
        pairStatus.text = message
        pairStatus.setTextColor(if (error) Color.rgb(255, 179, 171) else muted)
    }

    private fun resumeTrackpad() {
        showTrackpad()
        if (foregroundActivity) paired?.let(connection::start)
    }

    private fun showTrackpad() {
        screen = Screen.TRACKPAD
        settingsBack.isEnabled = false
        buildRoot()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("NULLPTR", 12, accent, bold = true))
        heading.addView(label("Trackpad", 28, textColor, bold = true))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Pair") {
            val lastAddress = paired?.address?.toString()
            showPairing()
            addressField.setText(lastAddress)
        })
        header.addView(button("Settings", action = ::showSettings), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
        })
        root.addView(header)
        status = label("", 14, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status, spaced(top = 6))
        pad = TouchpadView(this, connection::send)
        pad.sensitivity = 0.4f + savedSensitivity() / 50f
        pad.invertScroll = prefs.getBoolean("invertScroll", false)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        root.addView(label("Tap to click · Hold to drag · Two fingers to scroll or right-click", 13, muted), spaced(top = 10))
        renderConnection(connection.status)
    }

    private fun showSettings() {
        if (::pad.isInitialized) pad.reset()
        screen = Screen.SETTINGS
        settingsBack.isEnabled = true
        buildRoot()
        val scroll = ScrollView(this)
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        scroll.addView(settings)
        settings.addView(label("NULLPTR", 12, accent, bold = true))
        settings.addView(label("Settings", 28, textColor, bold = true), spaced(top = 6))
        val savedSensitivity = savedSensitivity()
        val sensitivityValue = label(sensitivityLabel(savedSensitivity), 16, textColor)
        settings.addView(sensitivityValue, spaced(top = 24))
        val slider = SeekBar(this).apply {
            max = 100
            progress = savedSensitivity
            contentDescription = "Pointer sensitivity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    sensitivityValue.text = sensitivityLabel(progress)
                    if (fromUser) prefs.edit().putInt("sensitivity", progress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        settings.addView(slider, spaced(top = 8))
        settings.addView(Switch(this).apply {
            text = "Invert scroll"
            textSize = 16f
            setTextColor(textColor)
            minimumHeight = dp(48)
            isChecked = prefs.getBoolean("invertScroll", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("invertScroll", checked).apply()
            }
        }, spaced(top = 16))
        settings.addView(label("Reverse the direction of two-finger scrolling.", 14, muted))
        settings.addView(button("Back to trackpad", action = ::showTrackpad), spaced(top = 24))
    }

    private fun renderConnection(state: TrackpadConnection.Status) {
        if (screen != Screen.TRACKPAD || !::pad.isInitialized) return
        val ready = state == TrackpadConnection.Status.CONNECTED
        if (pad.isEnabled && !ready) pad.reset()
        pad.isEnabled = ready
        pad.invalidate()
        val address = paired?.address
        status.text = when (state) {
            TrackpadConnection.Status.IDLE -> address?.let { "Saved computer · $it" } ?: "No computer paired"
            TrackpadConnection.Status.CONNECTING -> "Connecting to $address…"
            TrackpadConnection.Status.CONNECTED -> "Connected · $address"
            TrackpadConnection.Status.RETRYING -> "Connection lost. Retrying…"
            TrackpadConnection.Status.UNPAIRED -> "Pairing expired or was replaced. Pair again."
        }
        status.setTextColor(if (ready) accent else muted)
    }

    private fun buildRoot() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val edges = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
                )
                view.setPadding(edges.left + dp(20), edges.top + dp(16), edges.right + dp(20), edges.bottom + dp(16))
                insets
            }
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun button(text: String, prominent: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minimumHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(if (prominent) backgroundColor else textColor)
            background = GradientDrawable().apply {
                setColor(if (prominent) accent else Color.rgb(37, 48, 59))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun savedSensitivity() = prefs.getInt("sensitivity", 30).coerceIn(0, 100)
    private fun sensitivityLabel(progress: Int) = "Pointer sensitivity · ${40 + progress * 2}%"
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun spaced(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
