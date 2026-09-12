# 🎯 Gyroscope (Motion Aim) — the complete guide

*Added in Bannerlator **2.8**.*

Motion aim lets you **aim by physically tilting your device**. Small wrist movements handle the fine
aim that a thumbstick is bad at, while the stick keeps doing the big turns — the same idea as gyro
aiming on a Switch, DualSense or Steam Deck.

This guide covers every setting, what each one is actually for, and how to fix it when it feels
wrong. No prior knowledge assumed.

---

## Contents

- [Before you start](#before-you-start)
- [Quick start](#quick-start)
- [Where the settings live](#where-the-settings-live)
- [Motion Mode — Rate vs Tilt to Aim](#motion-mode--rate-vs-tilt-to-aim)
- [Motion Target — Right stick / Left stick / Mouse](#motion-target--right-stick--left-stick--mouse)
- [Activator & Activation — when the gyro is listening](#activator--activation--when-the-gyro-is-listening)
- [Tuning — Sensitivity, Deadzone, Smoothing, Invert](#tuning--sensitivity-deadzone-smoothing-invert)
- [Calibration — fixing drift](#calibration--fixing-drift)
- [Recenter](#recenter)
- [Combinations that aren't available](#combinations-that-arent-available)
- [Troubleshooting](#troubleshooting)
- [Under the hood](#under-the-hood)
- [Credits](#credits)

---

## Before you start

**You need a device with a gyroscope.** Most phones have one; many tablets and some budget devices
don't. If yours doesn't, the gyro controls simply stay inert — nothing breaks, nothing moves.

**Tilt to Aim additionally needs a rotation-vector sensor.** If your device lacks one, that mode is
greyed out with *"Not available — this device has no rotation-vector sensor."* Everything else still
works.

> Bannerlator prefers the **game** rotation vector, which fuses only the gyroscope and accelerometer — so a
> speaker magnet or a magnetic case can't drag your aim around. It falls back to the standard
> (compass-assisted) rotation vector on the handful of devices that don't offer the game one.

Motion aim is **on by default**, but it does nothing until you hold its activator button — out of
the box, that's **L1**. So if you've never touched it, nothing has changed for you.

---

## Quick start

1. Launch a game and open the **in-game drawer** → **Controls** tab → **Gyro** sub-tab.
2. Leave **Motion mode** on **Rate** and **Apply gyro to** on **Right stick**.
3. In game, **hold L1** and tilt the device. The camera moves.
4. Too fast or too slow? Adjust **Sensitivity**. Drifting when you hold still? See
   [Calibration](#calibration--fixing-drift).

That's the whole feature in four steps. Everything below is refinement.

---

## Where the settings live

There are **four** places, and they do different jobs:

| Where | What it's for |
|---|---|
| **In-game drawer → Controls → Gyro** | Live tuning while you play. Changes apply instantly — this is where you'll actually dial it in. |
| **Container settings** | The defaults for every game in that container. |
| **Per-game shortcut settings** | Overrides for one game. Saved like the rest of the per-game presets. |
| **Input Controls screen → Gyroscope** | **Calibration only** (see below). This is device-wide, not per game. |

Settings are saved **per container and per game**, so a twitchy shooter and a slow strategy game can
each have their own feel. Calibration is deliberately *not* per game — it's a property of your
hardware, so it's measured once and applies everywhere.

---

## Motion Mode — Rate vs Tilt to Aim

This is the most important choice, and the two modes feel completely different.

### 🔄 Rate (default)

**How fast you're rotating** becomes stick deflection. Tilt quickly → the camera moves quickly. Stop
moving → the camera stops, even if the device is still tilted.

- **Best for:** almost everything. Shooters, third-person games, general aiming.
- **Feels like:** a mouse. You "flick" and it stops when you stop.
- **Why it's the default:** you can hold the device at any comfortable angle and it doesn't matter —
  only movement counts, so there's no "correct" posture to maintain.

### 📐 Tilt to Aim (orientation mode)

**The angle you're holding the device at** becomes stick deflection. Tilt 20° right and *hold it
there* — the camera keeps turning right for as long as you hold that pose. Return to centre and it
stops.

- **Best for:** slow, sustained panning, and vehicle/flight-style aiming.
- **Feels like:** a physical stick that you're leaning.
- **The catch:** it needs a centre to measure from. That centre is captured **the moment you
  activate**, so whatever pose you're in when you press the button becomes "neutral." Release and
  press again to re-take it — or use [Recenter](#recenter).

> **In-app description:** *"The stick follows the angle you hold the device at, so a held tilt keeps
> aiming."*

**Which should you pick?** Start with Rate. Switch to Tilt to Aim only if you specifically want a
held tilt to keep the camera turning.

---

## Motion Target — Right stick / Left stick / Mouse

What the gyro actually drives.

### 🕹️ Right stick (default)
Standard camera/aim stick in virtually every controller game. **This is the one you want** unless
you have a specific reason otherwise.

### 🕹️ Left stick
Movement stick. Unusual for aiming, but useful for games that map look-control to the left stick, or
for a game where you'd rather tilt to *steer* — driving and flying games in particular.

### 🖱️ Mouse
Moves the **pointer** instead of a stick. Use this for:
- The **Wine desktop** and windowed applications, where there is no gamepad at all.
- **Mouse-look games** that read raw mouse input rather than a controller.

Bannerlator picks the right delivery automatically: in mouse-look (captured) mode it sends real
relative mouse motion to the game; on the desktop it moves the X pointer directly. You don't
configure this — it follows whatever the running app is doing.

> **In-app description:** *"Moves the pointer instead of a stick — use for the desktop and mouse-look
> games."*

---

## Activator & Activation — when the gyro is listening

A gyro that's always live is exhausting: every time you shift in your seat, your aim moves. So the
gyro is normally **gated behind a button**.

### Activator Button
Which button gates it: **L1** (default), **L2**, **R1**, **R3**, or **Always On**.

Pick a button you can hold comfortably while playing. **L2** is a natural choice in shooters if it's
already your aim-down-sights trigger — gyro then switches on exactly when you're aiming.
**Always On** removes the gate entirely; the gyro runs constantly.

### Activation — Hold vs Toggle

| | Behaviour | Good for |
|---|---|---|
| **Hold** *(default)* | Gyro runs **while the button is down**. Release and it stops. | Bursts of precision — ADS aiming, quick corrections. |
| **Toggle** | **Tap** to latch the gyro on; tap again to turn it off. | Long sessions where you want it on permanently without holding anything. |

> **In-app description:** *"Tap the button to turn the gyro on, tap again to turn it off."*

Activation is **greyed out** when the Activator is **Always On** — there's no button to hold or latch. It's greyed rather than hidden so it's clear the setting still exists and comes back when you pick a button again.

**Note on Toggle:** the button press is detected on the sensor's own timing, so an extremely fast tap
(under ~20 ms) can occasionally be missed. Normal presses run 60–150 ms, so in practice you won't
notice — but if a tap ever seems ignored, that's why. Just tap again.

---

## Tuning — Sensitivity, Deadzone, Smoothing, Invert

| Setting | Default | What it does | Turn it up if… | Turn it down if… |
|---|---|---|---|---|
| **Sensitivity** | 2.0 | Overall gain — how much camera movement you get per unit of tilt. | You have to wave the device to turn. | Small movements overshoot. |
| **Deadzone** | 0.05 | Ignores movement below this threshold, so hand tremor doesn't nudge your aim. | Your aim creeps while you try to hold still. | Slow, deliberate movements get ignored. |
| **Smoothing** | 0.5 | Low-pass filter. Higher = smoother but slightly laggier; 0 = raw and immediate. | The camera feels jittery. | It feels mushy or delayed. |
| **Invert X / Invert Y** | Off | Flips left/right or up/down. | — | — |

**Deadzone means something slightly different per mode**, and this is intentional:
- In **Rate** mode it's a minimum *rotation speed* — below it, you're considered still.
- In **Tilt to Aim** it's a dead *cone* around the captured centre — the default 0.05 works out to
  roughly **2.9° of slop** before anything moves.

There's deliberately one Deadzone setting rather than two, because both describe the same thing: how
steady your hands are.

**Tuning order that works:** Sensitivity first (get the speed right) → Deadzone second (kill the
creep) → Smoothing last (polish the feel). Adjust live from the in-game drawer while actually
playing; it's far faster than guessing from a menu.

---

## Calibration — fixing drift

**The problem it solves:** almost every gyroscope reports a tiny bit of rotation even when it's
completely still. That residual is called *zero-rate bias*, and it's why aim can slowly creep in one
direction while you're not touching anything.

**The fix:** measure that resting error once and subtract it from every reading afterwards.

### How to calibrate
1. Go to **Input Controls** → **Gyroscope**.
2. **Rest the device on a flat surface** and don't touch it.
3. Tap calibrate. It samples for about 1.5 seconds and stores the result.

### What can happen
- **Success** — bias measured and stored. If it's tiny, you'll be told there was essentially nothing
  to remove, which means your device is already well-behaved. That's a good result, not a failure.
- **"Hold the device still and try again"** — it detected motion and **refused to store anything**,
  deliberately: averaging your movement into the bias would make drift *worse*. Put it down properly
  and retry.
- **"Couldn't sample — try again"** — not enough sensor data arrived in the window.

There's also a **clear** option to drop the stored calibration back to zero if you ever want to undo
one.

### Good to know
- Calibration is stored **against the specific device it was measured on**, so if a backup restores
  your settings to a different phone, that phone won't inherit a bias that doesn't apply to it.
- The bias is subtracted **before** the deadzone, on purpose. If it were subtracted after, you'd have
  to raise the deadzone just to cancel the drift — and a big deadzone is exactly what makes fine aim
  feel mushy. Calibrating properly lets you keep the deadzone small.
- Calibration only affects **Rate** mode. Tilt to Aim measures an *angle*, not a speed, so a
  rate-offset doesn't apply — and that mode cancels any constant offset anyway when it captures its
  centre.

---

## Recenter

Available in the in-game drawer: **"Makes the pose you are holding now the centre."**

Only relevant in **Tilt to Aim**. If your neutral position has drifted — you've shifted on the couch,
or you activated while holding the device at an awkward angle — tap Recenter to make your current
pose the new zero.

You can also just release and re-press the activator, which re-captures centre automatically.

---

## Combinations that aren't available

Two combinations are intentionally blocked, and the app tells you why:

- **Mouse target + Tilt to Aim** — *"Mouse is not available in Tilt to aim — a held tilt would run
  the pointer off screen."* In orientation mode a held tilt means sustained movement; for a pointer
  that means it slides to the edge and stays there. Pick a stick target to use Tilt to Aim.
- **Tilt to Aim without a rotation-vector sensor** — *"Not available — this device has no
  rotation-vector sensor."* Hardware limitation.

---

## Troubleshooting

**Nothing happens when I tilt.**
Check in order: is the game actually reading a gamepad? Are you **holding the activator** (L1 by
default)? Is **Enable Motion Aim** on? Does your device have a gyroscope? Remember that with **Hold**
activation, nothing moves until the button is down.

**My aim drifts on its own.**
Calibrate ([above](#calibration--fixing-drift)). If it still drifts after a clean calibration, raise
the **Deadzone** slightly.

**It's way too sensitive / not sensitive enough.**
**Sensitivity**. Note that Tilt to Aim is internally geared up relative to Rate mode, so switching
modes will change the feel at the same number — expect to re-tune when you switch.

**It's jittery.**
Raise **Smoothing**. If it then feels laggy, you've gone too far — back it off and raise Deadzone a
little instead.

**Camera goes the wrong way.**
**Invert X** / **Invert Y**.

**In Tilt to Aim, "centre" feels wrong.**
Tap **Recenter**, or release and re-press the activator. Also avoid activating while the device is
pitched very steeply — extreme angles are ambiguous to measure, and the mode holds its last value
rather than emitting a garbage reading.

**My settings didn't carry to another game.**
That's by design — gyro settings are per container and per game. Calibration is the only part that's
device-wide.

---

## Under the hood

For the curious. In **Rate** mode each sensor sample runs through, in this exact order:

1. **Subtract calibration bias** — first, for the reason explained above.
2. **Deadzone** — small movement becomes zero.
3. **Invert** — applied after the deadzone so an inverted axis doesn't fight it.
4. **Sensitivity** — gain.
5. **Low-pass filter** (Smoothing) — then clamped to the stick's range.
6. The filter tail is **snapped to exact zero** once motion stops, so the stick fully recentres
   instead of resting at a hair's-width deflection.

**Tilt to Aim** captures a zero reference on activation, measures the angular difference from it,
and applies the same deadzone → invert → gain → smoothing chain. It also guards against extreme
pitch angles, where the yaw measurement becomes mathematically unreliable, by holding the previous
value instead of emitting nonsense.

**Mouse** mode accumulates fractional pixel movement rather than truncating it, so slow tilts still
move the pointer instead of rounding away to nothing.

---

## Credits

The gyro implementation is derived from
**[WinNative](https://github.com/WinNative-Emu/WinNative)**'s rate-mode gyro: the
sensor→stick pipeline, the axis and sign conventions, and the fractional-remainder accumulator that
keeps slow movement from rounding away. GPL-3.0, same as this project — thank you.

Bannerlator adapted it to its own gamepad-injection path and extended the mouse target to drive the
X pointer directly, so motion control also works on a Wine container desktop and not only in
captured mouse-look games. The Tilt-to-Aim mode, calibration, Hold/Toggle activation and per-game
persistence were added on top.
