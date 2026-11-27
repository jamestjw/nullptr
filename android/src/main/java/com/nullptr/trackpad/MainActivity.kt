package com.nullptr.trackpad

import android.app.AlertDialog
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
import android.widget.HorizontalScrollView
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
import androidx.core.view.isVisible
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private enum class Screen { PAIRING, TRACKPAD, DEVICES, SETTINGS }

    private val backgroundColor = Color.rgb(17, 22, 28)
    private val accent = Color.rgb(168, 233, 202)
    private val textColor = Color.rgb(236, 243, 249)
    private val muted = Color.rgb(170, 183, 195)
    private val prefs by lazy { getSharedPreferences("trackpad", Context.MODE_PRIVATE) }
    private val computers by lazy { SavedComputers(prefs) }
    private lateinit var connection: TrackpadConnection
    private lateinit var root: LinearLayout
    private lateinit var pad: TouchpadView
    private lateinit var status: TextView
    private lateinit var pairAgain: Button
    private lateinit var keyboardInput: KeyboardInput
    private lateinit var keyboardButton: Button
    private lateinit var specialKeys: HorizontalScrollView
    private lateinit var keyboardNotice: TextView
    private val expireKeyboardNotice = Runnable { keyboardNotice.isVisible = false }
    private lateinit var addressField: EditText
    private lateinit var codeField: EditText
    private lateinit var pairStatus: TextView
    private lateinit var submit: Button
    private lateinit var scan: Button
    private var cancel: Button? = null
    private var screen = Screen.PAIRING
    private var pairingReturn = Screen.DEVICES
    private var foregroundActivity = false
    private val navigationBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (screen) {
                Screen.PAIRING -> if (submit.isEnabled) leavePairing()
                Screen.DEVICES, Screen.SETTINGS -> showTrackpad()
                Screen.TRACKPAD -> Unit
            }
        }
    }
    private val scanPairing = registerForActivityResult(ScanContract()) { result ->
        if (screen == Screen.PAIRING && submit.isEnabled) result.contents?.let(::readPairingQr)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        connection = TrackpadConnection(::renderConnection, ::showKeyboardIssue)
        onBackPressedDispatcher.addCallback(this, navigationBack)
        val savedScreen = savedInstanceState?.getString("screen")
        when {
            computers.active == null || savedScreen == Screen.PAIRING.name -> {
                val fromTrackpad = savedInstanceState?.getString("pairingReturn") == Screen.TRACKPAD.name
                showPairing(savedInstanceState?.getString("address"), fromTrackpad = fromTrackpad)
            }
            savedScreen == Screen.DEVICES.name -> showDevices()
            savedScreen == Screen.SETTINGS.name -> showSettings()
            else -> showTrackpad()
        }
    }

    override fun onStart() {
        super.onStart()
        foregroundActivity = true
        startSelected()
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
        if (screen == Screen.PAIRING) {
            outState.putString("address", addressField.text.toString())
            outState.putString("pairingReturn", pairingReturn.name)
        }
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

    private fun showPairing(address: String? = null, fromTrackpad: Boolean = false) {
        leaveTrackpad()
        screen = Screen.PAIRING
        pairingReturn = if (fromTrackpad) Screen.TRACKPAD else Screen.DEVICES
        navigationBack.isEnabled = computers.active != null
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
            setText(address)
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
        cancel = computers.active?.let {
            val text = if (fromTrackpad) "Back to trackpad" else "Back to devices"
            button(text, action = ::leavePairing).also { form.addView(it, spaced(top = 8)) }
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
                computers.pair(computer)
                showTrackpad()
                startSelected()
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

    private fun leavePairing() {
        if (pairingReturn == Screen.TRACKPAD) showTrackpad() else showDevices()
        startSelected()
    }

    private fun startSelected() {
        if (foregroundActivity && screen != Screen.PAIRING) computers.active?.let(connection::start)
    }

    private fun leaveTrackpad() {
        if (screen != Screen.TRACKPAD) return
        pad.reset()
        closeKeyboard(commit = false)
    }

    private fun showTrackpad() {
        screen = Screen.TRACKPAD
        navigationBack.isEnabled = false
        buildRoot()
        val trackpadRoot = root
        fun acceptsInput() = foregroundActivity && screen == Screen.TRACKPAD && root === trackpadRoot
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(label("NULLPTR", 12, accent, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("Devices", action = ::showDevices))
        toolbar.addView(button("Settings", action = ::showSettings), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
        })
        header.addView(toolbar)
        header.addView(label("Trackpad", 28, textColor, bold = true))
        root.addView(header)
        status = label("", 14, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status, spaced(top = 6))
        pairAgain = button("Pair again") { showPairing(computers.active?.address?.toString(), fromTrackpad = true) }
        root.addView(pairAgain, spaced(top = 8))
        pad = TouchpadView(this) { event ->
            if (acceptsInput()) {
                if (event is InputEvent.Button && event.down) keyboardInput.finishBeforePointerPress()
                connection.send(event)
            }
        }
        pad.sensitivity = 0.4f + savedSensitivity() / 50f
        pad.invertScroll = prefs.getBoolean("invertScroll", false)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        val gestureHelp = label("Tap to click · Hold to drag · Two fingers to scroll or right-click", 13, muted)
        root.addView(gestureHelp, spaced(top = 10))
        keyboardInput = KeyboardInput(this) { event ->
            acceptsInput() && connection.sendKeyboard(event)
        }.apply { isVisible = false }
        val trackpadKeyboard = keyboardInput
        root.addView(keyboardInput, LinearLayout.LayoutParams(1, 1))
        val keyboardRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        specialKeys = HorizontalScrollView(this).apply {
            isVisible = false
            isScrollbarFadingEnabled = false
            contentDescription = "Special keys. Scroll for more."
            addView(LinearLayout(this@MainActivity).apply {
                KeyboardKey.entries.forEach { key ->
                    addView(button(key.label) { trackpadKeyboard.tap(key) }.apply {
                        contentDescription = key.wireName.replaceFirstChar { it.uppercase() }
                        minWidth = dp(48)
                        minimumWidth = dp(48)
                        setPadding(dp(10), 0, dp(10), 0)
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
                }
            })
        }
        var imeVisible = false
        keyboardButton = button("Keyboard") {
            if (!acceptsInput()) return@button
            if (trackpadKeyboard.isVisible) {
                closeKeyboard(commit = true)
            } else {
                specialKeys.isVisible = true
                keyboardInput.show()
                keyboardButton.text = "Hide"
            }
        }
        keyboardRow.addView(keyboardButton)
        keyboardRow.addView(specialKeys, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(keyboardRow, spaced(top = 8))
        keyboardNotice = label("", 13, Color.rgb(255, 205, 142)).apply {
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(keyboardNotice, spaced(top = 6))
        ViewCompat.setOnApplyWindowInsetsListener(keyboardRow) { _, insets ->
            if (root !== trackpadRoot) return@setOnApplyWindowInsetsListener insets
            val wasVisible = imeVisible
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (wasVisible && !imeVisible) closeKeyboard(commit = true)
            header.isVisible = !imeVisible
            status.isVisible = !imeVisible
            pairAgain.isVisible = !imeVisible && connection.status == TrackpadConnection.Status.UNPAIRED
            gestureHelp.isVisible = !imeVisible
            insets
        }
        renderConnection(connection.status)
    }

    private fun closeKeyboard(commit: Boolean) {
        if (!keyboardInput.isVisible) return
        keyboardInput.dismiss(commit)
        specialKeys.isVisible = false
        keyboardButton.text = "Keyboard"
    }

    private fun showKeyboardIssue(issue: TrackpadConnection.KeyboardIssue) {
        if (screen != Screen.TRACKPAD) return
        keyboardNotice.text = when (issue) {
            TrackpadConnection.KeyboardIssue.UNAVAILABLE -> "The desktop keyboard is unavailable."
            TrackpadConnection.KeyboardIssue.MODIFIERS_ACTIVE -> "Input skipped. Release modifier keys on the desktop."
            TrackpadConnection.KeyboardIssue.UNSUPPORTED_LAYOUT -> "Some input is not available in the desktop keyboard layout."
        }
        keyboardNotice.isVisible = true
        keyboardNotice.removeCallbacks(expireKeyboardNotice)
        keyboardNotice.postDelayed(expireKeyboardNotice, 5_000)
    }

    private fun showDevices() {
        leaveTrackpad()
        screen = Screen.DEVICES
        navigationBack.isEnabled = true
        buildRoot()
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        scroll.addView(list)
        list.addView(label("NULLPTR", 12, accent, bold = true))
        list.addView(label("Devices", 28, textColor, bold = true), spaced(top = 6))
        list.addView(button("Add computer", prominent = true) { showPairing() }, spaced(top = 16))
        computers.all.forEach { computer ->
            val active = computer == computers.active
            list.addView(label(computer.displayName, 18, textColor, bold = true), spaced(top = 18))
            if (computer.name != null) list.addView(label(computer.address.toString(), 14, muted))
            if (active) list.addView(label("Current computer", 14, accent))
            val actions = LinearLayout(this)
            if (!active) {
                actions.addView(button("Use") {
                    computers.select(computer.address)
                    startSelected()
                    showTrackpad()
                }.apply { contentDescription = "Use ${computer.displayName}" })
            }
            actions.addView(button("Manage") { manageComputer(computer) }.apply {
                contentDescription = "Manage ${computer.displayName}"
            }, LinearLayout.LayoutParams(-2, -2).apply { if (!active) marginStart = dp(8) })
            list.addView(actions, spaced(top = 6))
        }
        list.addView(button("Back to trackpad", action = ::showTrackpad), spaced(top = 24))
    }

    private fun manageComputer(computer: PairedComputer) {
        AlertDialog.Builder(this)
            .setTitle(computer.displayName)
            .setItems(arrayOf("Rename", "Pair again", "Forget")) { _, action ->
                when (action) {
                    0 -> renameComputer(computer)
                    1 -> showPairing(computer.address.toString())
                    2 -> forgetComputer(computer)
                }
            }
            .show()
    }

    private fun renameComputer(computer: PairedComputer) {
        val name = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(48))
            hint = computer.address.toString()
            setText(computer.name)
            setSelection(length())
        }
        val content = LinearLayout(this).apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(name, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename computer")
            .setMessage("Leave the name blank to use its address.")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                computers.rename(computer.address, name.text.toString())
                showDevices()
            }
            .create()
        name.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun forgetComputer(computer: PairedComputer) {
        AlertDialog.Builder(this)
            .setTitle("Forget ${computer.displayName}?")
            .setMessage("You'll need a new pairing code to use this computer again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                val wasActive = computer == computers.active
                computers.forget(computer.address)
                if (computers.active == null) {
                    showPairing()
                } else {
                    if (wasActive) startSelected()
                    showDevices()
                }
            }
            .show()
    }

    private fun showSettings() {
        leaveTrackpad()
        screen = Screen.SETTINGS
        navigationBack.isEnabled = true
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
        val keyboardReady = ready && connection.supportsKeyboard
        if (!keyboardReady) {
            closeKeyboard(commit = false)
            keyboardNotice.removeCallbacks(expireKeyboardNotice)
            expireKeyboardNotice.run()
        }
        keyboardButton.isEnabled = keyboardReady
        val target = computers.active?.displayName
        status.text = when (state) {
            TrackpadConnection.Status.IDLE -> "Saved computer · $target"
            TrackpadConnection.Status.CONNECTING -> "Connecting to $target…"
            TrackpadConnection.Status.CONNECTED -> if (keyboardReady) "Connected · $target"
                else "Connected · $target\nUpdate the companion to use Keyboard."
            TrackpadConnection.Status.RETRYING -> "Connection lost · $target\nRetrying…"
            TrackpadConnection.Status.UNPAIRED -> "Pairing expired or was replaced · $target"
        }
        pairAgain.isVisible = state == TrackpadConnection.Status.UNPAIRED
        status.setTextColor(if (ready) accent else muted)
    }

    private fun buildRoot() {
        if (::keyboardNotice.isInitialized) keyboardNotice.removeCallbacks(expireKeyboardNotice)
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
