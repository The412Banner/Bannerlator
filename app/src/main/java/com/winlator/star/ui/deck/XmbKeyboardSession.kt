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

/**
 * One text field's typing session with the XMB keyboard: what Compose handed over when the field asked for the keyboard
 * (its text, its IME options and where to send edits) and the edits the keyboard sends back. [value] is kept current by
 * the host as the field changes, so the keyboard's preview always shows the real text and cursor.
 */
@Stable
internal class XmbKeyboardSession(
    initial: TextFieldValue,
    val imeOptions: ImeOptions,
    private val sendEdits: (List<EditCommand>) -> Unit,
    private val sendAction: (ImeAction) -> Unit,
) {
    var value by mutableStateOf(initial)

    /** Types [text] at the cursor, replacing a selection. */
    fun commit(text: String) = sendEdits(listOf(CommitTextCommand(text, 1)))

    /** Deletes the selection, or the character before the cursor. */
    fun backspace() {
        if (!value.selection.collapsed) commit("") else if (value.selection.start > 0) sendEdits(listOf(DeleteSurroundingTextCommand(1, 0)))
    }

    /** Moves the cursor [delta] characters, kept inside the text. */
    fun moveCursor(delta: Int) {
        val at = (value.selection.end + delta).coerceIn(0, value.text.length)
        sendEdits(listOf(SetSelectionCommand(at, at)))
    }

    /** Runs the field's IME action (Search, Done, Go, Send, Next...); Android's own keyboard turns a single line's default into Done. */
    fun performAction() = sendAction(if (imeOptions.imeAction == ImeAction.Default && imeOptions.singleLine) ImeAction.Done else imeOptions.imeAction)
}
