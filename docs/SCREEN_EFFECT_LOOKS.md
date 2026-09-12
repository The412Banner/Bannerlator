# Screen Effect Looks — one-tap presets in the in-game drawer

A row of named chips at the top of the Graphics tab's **Screen Effects** section.
One tap sets the colour grade, the sharpening pass, the shader toggles and
debanding (and, for two of them, the scaling mode). Everything stays adjustable
afterwards — move any control the Look owns and the row falls back to a trailing
**Custom** chip. Tap the Look again to reapply it.

Nothing new is rendered: a Look is only a named set of values for controls the
compositor already has. The chips write the same local state and fire the same
apply callbacks the sliders and toggles use, so there is no second code path.

## Where it lives

| Piece | File |
|---|---|
| The Look table (single source of truth) | `app/src/main/java/com/winlator/star/ui/ScreenEffectLooks.kt` |
| Chip row widget (`LooksRow`) | `app/src/main/java/com/winlator/star/ui/XServerDrawer.kt` |
| GL block wiring (`applyGlLook`) | `XServerDrawer.kt`, inside `if (effectsSupported)` |
| Vulkan block wiring (`applyVkLook`) | `XServerDrawer.kt`, inside `if (vulkanSupported)` |
| Saturation state + widened callbacks | `app/src/main/java/com/winlator/star/ui/XServerDialogState.kt` |
| GL saturation shader | `app/src/main/java/com/winlator/star/renderer/effects/ColorEffect.java` |
| Vulkan saturation shader | `app/src/main/cpp/winlator/color.frag` (+ generated `color_frag.h`) |

The feature is present on **both** renderer paths, driven by the one table, so a
Look grades the same on OpenGL and on Vulkan. The SurfaceFlinger renderer scans
frames out directly and has no post-process pass, so it shows neither the Looks
row nor any of the controls it drives — unchanged from before.

## The Looks

`cas` is the sharpening level 0–100; **0 means the sharpening pass is off**.
`scaling` blank means the Look leaves the user's scaling mode alone.

| Look | Brightness | Contrast | Gamma | Saturation | CAS | Also switches on | Scaling |
|---|---|---|---|---|---|---|---|
| Off | 0 | 0 | 1.00 | 100 | 0 | — | — |
| Game Clarity | +2 | +12 | 1.00 | 108 | 55 | — | — |
| Vivid | +3 | +10 | 0.98 | 145 | 30 | Deband | — |
| Cinematic | −4 | +22 | 1.10 | 88 | 20 | FXAA · Deband | — |
| Competitive | +16 | +18 | 0.92 | 82 | 75 | — | — |
| Adaptive Sharpen | 0 | 0 | 1.00 | 100 | 85 | — | — |
| Filmic | −2 | +16 | 1.14 | 92 | 15 | FXAA · Deband | — |
| Arcade | +6 | +20 | 0.95 | 170 | 45 | Deband | — |
| Retro CRT | +6 | +14 | 1.05 | 115 | 0 | CRT · NTSC | — |
| Upscale Sharp | 0 | +6 | 1.00 | 104 | 70 | — | FSR |
| Pixel Clean | 0 | +8 | 1.00 | 100 | 0 | Deband | Nearest |
| Anime Edge | +2 | +10 | 1.00 | 125 | 50 | Toon · Deband | — |

Scaling-mode integers come from the picker both paths share
(`UpscalerModeButtons`: 0 None, 1 Linear, 2 Nearest, 3 SGSR, 4 FSR, 5 FSR (Fit),
6 Sharpen, 7 NIS), so `FSR = 4` and `Nearest = 2` on GL and Vulkan alike —
`ScreenEffectLooks.SCALING_FSR` / `SCALING_NEAREST`. If the two pickers ever
diverge, that pair of constants is the one place to fork.

## What a tap fans out to

Applying a Look drives **every** applier the values belong to, not just the
screen-effects one:

1. Colour grade + FXAA/CRT/Toon/NTSC → `onScreenEffectsApply` (GL) /
   `onVulkanScreenEffectsApply` (Vulkan).
2. Sharpening → `pushSgsrUpdate` (GL, the "Sharpen (CAS)" toggle + level) /
   `onCasApply` (Vulkan). `cas == 0` switches the pass off and leaves the level
   slider parked where the user last had it.
3. Debanding → `onDebandApply`, with the user's dither strength preserved.
4. Scaling → `onGlUpscalerApply` / `onUpscalerApply`, **only** when the Look
   names a mode.

Each of those also writes its value back into `XServerDialogState`, so the shared
state stays the single source of truth and the drawer is still truthful after it
is closed and reopened.

## Custom fallback

`selectedLook` is seeded by matching the live values against the table
(`ScreenEffectLooks.indexOfMatch`), so a session launched with effects already on
honestly shows **Custom** rather than claiming "Off"; a fresh session matches
"Off" and starts with that chip lit.

A manual change to a control the **active Look owns** clears the selection:
brightness, contrast, gamma, saturation, the sharpening toggle and its level, the
four shader toggles, debanding, and — only when the active Look names a scaling
mode — the scaling picker. Controls no Look touches (**HDR**, and the upscaler's
own "Sharpness" slider) deliberately do not drop you to Custom. Tapping the
Custom chip does nothing: Custom is a state you arrive at, not one you pick.

## Saturation

Saturation was the one knob the grade did not have. It is now on **both** paths,
same maths and same position in the chain, so Vivid and Arcade look identical on
OpenGL and Vulkan.

- Slider units: **0–200 percent, 100 = neutral** (0 = greyscale, 200 = double).
  The shader takes it normalised; the renderer divides by 100 the same way it
  already does for brightness and contrast.
- Applied **after** brightness/contrast and **before** gamma, as a luma-preserving
  mix towards Rec.709 grey:
  `mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, clamp(saturation, 0.0, 2.0))`.
  Both shaders document that ordering in their header.
- **Vulkan**: `saturation` is appended at the END of the Color push-constant block
  (`ColorPushConstants` 28 → 32 bytes), so the existing `brightness`/`contrast`/
  `gamma` offsets do not move; the block stays far under the 88-byte push-constant
  range the post pipeline layout reserves. `color_frag.h` is the checked-in SPIR-V
  and was regenerated with
  `glslangValidator -V --vn color_code -o color_frag.h color.frag`.
- The chain's "skip Color when neutral" test now also requires
  `saturation == 100`, so a Look that only moves saturation still switches the
  Color pass on. Same test in the GL `applyScreenEffects` (a neutral grade removes
  `ColorEffect` entirely).
- **GL**: `ColorEffect` gained a `saturation` field (default 1.0f), the uniform
  name, one clamp in `use()`, and two lines in the inline GLSL.

Every new argument defaults to neutral (100 in slider units, 1.0 normalised), so
callers that do not care behave exactly as before.

## Persistence

Session-live, like its neighbours in the drawer. The Look selection is not saved,
and the on-disk `screen_effect_profiles` format is deliberately **unchanged** —
saved profiles carry brightness/contrast/gamma and the shader flags as they always
have, and load with saturation at neutral.

Note that a Look rides the existing appliers, so it inherits whatever they already
persist: `onSgsrUpdate` / `onCasApply` remember the sharpening toggle and level per
game (#382), `onDebandApply` remembers debanding, and `onGlUpscalerApply` /
`onUpscalerApply` remember the scaling mode. That is unchanged behaviour of those
callbacks — no new save keys, no format change — and it is why a Look's sharpening
and scaling survive a relaunch while its colour grade does not.

## Layout

The drawer is a fixed-width shell (`380.dp` = a `60.dp` rail + a `1.5.dp` seam +
the content column, which carries `14.dp` padding on each side ⇒ ~290 dp of
usable width). That width does not change with orientation, so the chip grid
wraps identically in portrait and landscape; only the length of the vertical
scroll differs. The row reuses the drawer's existing `ToggleChipGrid`, three per
row, whose `IntrinsicSize.Min` + centred short-row padding keep the grid aligned
when the longest label ("Adaptive Sharpen") wraps to two lines. Twelve Looks fill
four even rows; the Custom chip adds a centred thirteenth.
