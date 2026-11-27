package com.nullptr.trackpad

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible

@SuppressLint("ViewConstructor")
class KeyboardInput(
    context: Context,
    private val send: (InputEvent.Keyboard) -> Boolean,
) : View(context) {
    private val ime = context.getSystemService(InputMethodManager::class.java)
    private var session: Session? = null

    init {
        isFocusableInTouchMode = true
        isSaveEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show() {
        isVisible = true
        if (requestFocus()) ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun dismiss() {
        session?.discard()
        ime.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
        isVisible = false
    }

    fun tap(key: KeyboardKey) {
        val active = session
        if (key == KeyboardKey.BACKSPACE && active != null) {
            active.backspace()
        } else {
            active?.finishComposingText()
            send(InputEvent.KeyTap(key))
        }
    }

    override fun onCheckIsTextEditor(): Boolean = isVisible

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!onCheckIsTextEditor()) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (Build.VERSION.SDK_INT >= 26) {
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        session?.discard()
        return Session().also { session = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        session?.handleKey(event) == true || super.onKeyDown(keyCode, event)

    private inner class Session : BaseInputConnection(this@KeyboardInput, true) {
        private val buffer = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        private var batchDepth = 0
        private val active get() = session === this

        override fun getEditable() = buffer

        fun discard() {
            if (active) session = null
            clearBuffer()
        }

        override fun closeConnection() {
            discard()
            super.closeConnection()
        }

        override fun beginBatchEdit(): Boolean {
            if (!active) return false
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (!active || batchDepth == 0) return false
            batchDepth--
            if (batchDepth == 0) updateSelection()
            return batchDepth > 0
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            if (!active) return false
            return super.setSelection(start, end).also { updateSelection() }
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
            active && super.setComposingText(text, newCursorPosition)

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            active && super.setComposingRegion(start, end)

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
            commit { super.commitText(text, newCursorPosition) }

        override fun finishComposingText(): Boolean = commit { super.finishComposingText() }

        @RequiresApi(34)
        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?,
        ): Boolean = commit { super.replaceText(start, end, text, newCursorPosition, textAttribute) }

        private inline fun commit(edit: () -> Boolean): Boolean {
            if (!beginBatchEdit()) return false
            val changed = edit()
            if (changed && buffer.isNotEmpty()) {
                val committed = buffer.toString()
                clearBuffer()
                sendText(committed)
            }
            endBatchEdit()
            return changed
        }

        private fun clearBuffer() {
            buffer.clear()
            buffer.clearSpans()
            Selection.setSelection(buffer, 0)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            delete(beforeLength, afterLength, codePoints = false)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            delete(beforeLength, afterLength, codePoints = true)

        private fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
            if (!active || before < 0 || after < 0) return false
            val selectionStart = Selection.getSelectionStart(buffer)
            val selectionEnd = Selection.getSelectionEnd(buffer)
            val start = minOf(selectionStart, selectionEnd)
            val end = maxOf(selectionStart, selectionEnd)
            val availableBefore = if (codePoints) Character.codePointCount(buffer, 0, start) else start
            val availableAfter = if (codePoints) Character.codePointCount(buffer, end, buffer.length)
                else buffer.length - end
            val localBefore = minOf(before, availableBefore)
            val localAfter = minOf(after, availableAfter)
            val from = if (codePoints) Character.offsetByCodePoints(buffer, start, -localBefore)
                else start - localBefore
            val to = if (codePoints) Character.offsetByCodePoints(buffer, end, localAfter)
                else end + localAfter
            buffer.delete(end, if (localAfter > 0 && splitsSurrogatePair(to)) to + 1 else to)
            buffer.delete(if (localBefore > 0 && splitsSurrogatePair(from)) from - 1 else from, start)
            updateSelection()
            repeat(before - localBefore) {
                if (!send(InputEvent.KeyTap(KeyboardKey.BACKSPACE))) return false
            }
            return true
        }

        private fun splitsSurrogatePair(offset: Int) =
            buffer.getOrNull(offset - 1)?.isHighSurrogate() == true &&
                buffer.getOrNull(offset)?.isLowSurrogate() == true

        fun backspace() {
            val start = Selection.getSelectionStart(buffer)
            val end = Selection.getSelectionEnd(buffer)
            if (start == end) {
                deleteSurroundingTextInCodePoints(1, 0)
            } else {
                buffer.delete(minOf(start, end), maxOf(start, end))
                updateSelection()
            }
        }

        override fun performEditorAction(editorAction: Int): Boolean =
            finishComposingText() && send(InputEvent.KeyTap(KeyboardKey.ENTER))

        override fun sendKeyEvent(event: KeyEvent): Boolean =
            active && (event.action == KeyEvent.ACTION_UP || handleKey(event))

        fun handleKey(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN || event.isCtrlPressed || event.isAltPressed ||
                event.isMetaPressed
            ) return false
            val key = if (event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) KeyboardKey.ENTER
                else KeyboardKey.entries.find { it.keyCode == event.keyCode }
            if (key != null) {
                tap(key)
                return true
            }
            val codePoint = event.unicodeChar
            if (codePoint == 0 || !Character.isValidCodePoint(codePoint)) return false
            finishComposingText()
            sendText(String(Character.toChars(codePoint)))
            return true
        }

        private fun updateSelection() {
            if (batchDepth != 0) return
            ime.updateSelection(
                this@KeyboardInput,
                Selection.getSelectionStart(buffer),
                Selection.getSelectionEnd(buffer),
                getComposingSpanStart(buffer),
                getComposingSpanEnd(buffer),
            )
        }
    }

    private fun sendText(text: String) {
        val chunk = StringBuilder()
        var size = 0
        fun flush(): Boolean {
            if (size == 0) return true
            val sent = send(InputEvent.Text(chunk.toString()))
            chunk.setLength(0)
            size = 0
            return sent
        }
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            when {
                codePoint == '\r'.code || codePoint == '\n'.code || codePoint == '\t'.code -> {
                    if (codePoint == '\r'.code && text.getOrNull(offset) == '\n') offset++
                    val key = if (codePoint == '\t'.code) KeyboardKey.TAB else KeyboardKey.ENTER
                    if (!flush() || !send(InputEvent.KeyTap(key))) return
                }
                !Character.isISOControl(codePoint) && codePoint !in 0xD800..0xDFFF -> {
                    chunk.appendCodePoint(codePoint)
                    size++
                    if (size == 64 && !flush()) return
                }
            }
        }
        flush()
    }
}
