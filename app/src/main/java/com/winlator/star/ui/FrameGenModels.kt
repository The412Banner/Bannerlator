package com.winlator.star.ui

// Single source of truth for which frame-gen interpolation models are user-selectable.
//
// bionic-fg v0.1.1 clean base ships 0-1, plus 3 (FSR3) and 4 (FSR3-v2) — our own FidelityFX-based
// optical-flow shaders rebuilt onto the clean base. Only model 2 (V2/GameScope) stays out: it
// depended on traced third-party shaders the clean base removed.
//
// Every FG interpolation-model selector (container editor, in-game drawer, …) derives its
// disabled state from this set, so re-enabling model 2 later is a one-line change here.
// The native layer maps any saved-but-unavailable model to model-0 (Default) behaviour, so
// already-saved selections stay safe at runtime — we keep the clamps at coerceIn(0, 4) and never
// rewrite the saved value on disk; the UI just shows those indices grayed-out until the models are back.
val AVAILABLE_FRAME_GEN_MODELS: Set<Int> = setOf(0, 1, 3, 4)

/** True when [model] is currently built into the bundled bionic-fg layer and thus selectable. */
fun isFrameGenModelAvailable(model: Int): Boolean = model in AVAILABLE_FRAME_GEN_MODELS
