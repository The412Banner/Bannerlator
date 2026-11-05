package com.winlator.star.ui.deck

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import com.winlator.star.core.GuestTyper

/**
 * What the XMB keyboard panel types into: a Compose text field ([XmbKeyboardSession]) or the running game
 * ([GuestKeySession]). The panel only draws [value] in its preview strip and sends its keys here.
 */
internal interface XmbKeyboardInput {
    /** The text and cursor the preview strip shows. */
    val value: TextFieldValue

    /** The field's keyboard type, line mode and action decide the layout and the action key. */
    val imeOptions: ImeOptions

    /** Types [text] at the cursor, replacing a selection. */
    fun commit(text: String)

    /** Deletes the selection, or the character before the cursor. */
    fun backspace()

    /** Moves the cursor [delta] characters. */
    fun moveCursor(delta: Int)

    /** Runs the field's action (Search, Done, Go, Send, Next...). */
    fun performAction()

    /** Types the clipboard's [text]. */
    fun paste(text: String)

    /** True when the keys of a keyboard (Esc and the up and down arrows) are wanted too; a Compose field has no use for them. */
    val hasNavigationKeys: Boolean get() = false

    /** Presses a key that is not a character; only called when [hasNavigationKeys] is true. */
    fun pressKey(key: GuestTyper.Key) {}
}

/**
 * One text field's typing session with the XMB keyboard: what Compose handed over when the field asked for the keyboard
 * (its text, its IME options and where to send edits) and the edits the keyboard sends back. [value] is kept current by
 * the host as the field changes, so the keyboard's preview always shows the real text and cursor.
 */
@Stable
internal class XmbKeyboardSession(
    initial: TextFieldValue,
    override val imeOptions: ImeOptions,
    private val sendEdits: (List<EditCommand>) -> Unit,
    private val sendAction: (ImeAction) -> Unit,
) : XmbKeyboardInput {
    override var value: TextFieldValue by mutableStateOf(initial)

    override fun commit(text: String) = sendEdits(listOf(CommitTextCommand(text, 1)))

    override fun backspace() {
        if (!value.selection.collapsed) commit("") else if (value.selection.start > 0) sendEdits(listOf(DeleteSurroundingTextCommand(1, 0)))
    }

    /** Kept inside the text. */
    override fun moveCursor(delta: Int) {
        val at = (value.selection.end + delta).coerceIn(0, value.text.length)
        sendEdits(listOf(SetSelectionCommand(at, at)))
    }

    /** Android's own keyboard turns a single line's default action into Done. */
    override fun performAction() = sendAction(if (imeOptions.imeAction == ImeAction.Default && imeOptions.singleLine) ImeAction.Done else imeOptions.imeAction)

    /** A single line has no line breaks, so they become spaces. */
    override fun paste(text: String) = commit(if (imeOptions.singleLine) text.replace("\r", "").replace('\n', ' ') else text)
}
