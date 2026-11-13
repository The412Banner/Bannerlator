package com.winlator.star.sound

/**
 * A moment in the app UI that can make a sound.
 *
 * [rank]: cues raised by one input (a button press, a focus move) are merged and only the highest rank
 * plays, so pressing A on a row that opens a dialog plays the dialog sound, not both. Rank 0 = an event
 * cue (notification, startup): never merged, plays as soon as it is raised.
 * [gapMs]: the same cue does not repeat closer than this (held D-pad, slider drags, typing).
 * [files]: Steam `steamui/sounds` file names, tried in order; the first one the pack has plays.
 */
enum class UiCue(val rank: Int, val gapMs: Long, vararg val files: String) {
    // Input cues
    NAVIGATE(10, 45, "deck_ui_navigation.wav"),
    NAVIGATE_EDGE(12, 150, "deck_ui_bumper_end_02.wav"),
    SCROLL(14, 90, "deck_ui_tile_scroll.wav"),
    TYPE(20, 25, "deck_ui_typing.wav"),
    SLIDER_UP(30, 60, "deck_ui_slider_up.wav"),
    SLIDER_DOWN(30, 60, "deck_ui_slider_down.wav"),
    VOLUME(30, 60, "deck_ui_volume.wav"),
    SELECT(40, 0, "deck_ui_default_activation.wav"),
    BACK(40, 0, "confirmation_negative.wav"),
    OPTIONS(45, 0, "deck_ui_misc_10.wav", "deck_ui_show_modal.wav"),
    TOGGLE_ON(50, 0, "deck_ui_switch_toggle_on.wav"),
    TOGGLE_OFF(50, 0, "deck_ui_switch_toggle_off.wav"),
    CONFIRM(55, 0, "confirmation_positive.wav"),
    CANCEL(55, 0, "confirmation_negative.wav"),
    DIALOG_OPEN(60, 0, "deck_ui_show_modal.wav"),
    DIALOG_CLOSE(60, 0, "deck_ui_hide_modal.wav"),
    TAB(70, 0, "deck_ui_tab_transition_01.wav"),
    MENU_OPEN(80, 0, "deck_ui_side_menu_fly_in.wav"),
    MENU_CLOSE(80, 0, "deck_ui_side_menu_fly_out.wav"),
    PAGE_IN(85, 0, "deck_ui_into_game_detail.wav"),
    PAGE_OUT(85, 0, "deck_ui_out_of_game_detail.wav"),
    LAUNCH(100, 0, "deck_ui_launch_game.wav"),

    // Event cues
    TOAST(0, 300, "deck_ui_toast.wav"),
    TOAST_DESKTOP(0, 300, "desktop_toast_default.wav", "deck_ui_toast.wav"),
    TOAST_SHORT(0, 300, "desktop_toast_short.wav", "deck_ui_toast.wav"),
    MESSAGE(0, 300, "deck_ui_message_toast.wav"),
    TASK_START(0, 300, "deck_ui_misc_01.wav"),
    TASK_DONE(0, 300, "deck_ui_misc_08.wav"),
    ADDED(0, 300, "pop_sound.wav"),
    ERROR(0, 300, "bumper_end.wav"),
    ACHIEVEMENT(0, 300, "deck_ui_achievement_toast.wav"),
    SCREENSHOT(0, 300, "camera1.wav"),
    RECORD_START(0, 300, "recording_start.wav"),
    RECORD_STOP(0, 300, "recording_stop.wav"),
    RECORD_MARK(0, 300, "recording_highlight.wav"),
    FRIEND_ONLINE(0, 300, "ui_steam_smoother_friend_online.m4a"),
    FRIEND_IN_GAME(0, 300, "ui_steam_smoother_friend_join.m4a"),
    FRIEND_MESSAGE(0, 300, "ui_steam_message_old_smooth.m4a"),
    CHAT_MESSAGE(0, 300, "steam_chatroom_notification.m4a"),
    CHAT_MENTION(0, 300, "steam_at_mention.m4a"),
    TIMER(0, 1000, "timer_expired_alarm.wav"),
    STARTUP(0, 5000, "steam_os_startup.wav");

    val isEvent: Boolean get() = rank == 0
}
