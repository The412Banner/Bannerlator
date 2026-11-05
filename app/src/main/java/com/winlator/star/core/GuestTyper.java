package com.winlator.star.core;

/**
 * Types into the running guest for the XMB keyboard, on whichever display backend the session uses. Every method may be
 * called from the UI thread and returns at once: the key strokes are made in order on a worker thread of their own, with
 * short pauses a game's input polling can see.
 */
public interface GuestTyper {
    /** Keys that are not characters. */
    enum Key { ENTER, TAB, BACKSPACE, ESCAPE, LEFT, RIGHT, UP, DOWN }

    /** Types the text character by character; a newline is Enter, a tab is Tab. */
    void type(String text);

    /** Presses and releases one key. */
    void press(Key key);

    /** Drops what is still queued and lets go of every key held, so nothing stays pressed in the guest. */
    void releaseAll();

    /** Like {@link #releaseAll}, and the worker thread ends. The typer is not used afterwards. */
    void close();
}
