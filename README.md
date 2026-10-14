# Mate

A camera app that puts UI, design and feel first. Kotlin + Jetpack Compose + CameraX.

## Design
- **Pill shutter** with an outer ring, in three looks – tap the halftone-dots button to cycle
  Mint (light panel) → Mono (dark, round) → Sun (dark, yellow with "+").
- **Glass focal-length pill** (24mm / 48mm / ~77mm). Tap to cycle with a smooth zoom animation.
- Rule-of-thirds grid (long-press the dots button to hide/show).
- Portrait and landscape layouts (controls move to the side).
- **Collection** (tap the thumbnail): fanned cards, "Most viewed", and a dark sheet you can drag up
  to the "Camera roll" view.

## Camera features
Tap the **halftone dots** button to open the tools sheet:
- **Filters** – Original, Vivid, Mono, Noir, Warm, Cool, Fade, Sepia, Punch (live preview, baked into saved photos).
- **Exposure** – Auto with EV slider, or **Manual ISO + shutter speed**; white balance presets.
- **Grid** – Off, 3x3, 4x4, Golden ratio, Center cross.
- **More** – Flash / torch, timer (3s, 10s), video quality (720p / 1080p / 4K), shutter style, flip camera.
- **Video** – switch PHOTO / VIDEO at the top; tap the shutter to start/stop. Saved to `Movies/Mate`.

## Pro tools
- **Recent-shot thumbnail**: wide rounded "mini screen" with a dark bezel and gloss; crossfades to each new shot.
- **AF / MF pill** (top right, glowing yellow in AF): tap to switch to manual focus. In MF a **focus ruler** appears;
  drag left = near, right = far (haptic detent on every tick). Also available as a slider in *Pro*.
- **Level**: spirit-level line that turns yellow and ticks when the horizon is straight.
- **Histogram**: live luminance histogram.
- **Exposure + white-balance lock**, **mirror selfies**, **capture priority** (speed / quality), **JPEG quality** (85 / 95 / 100).
  All in the tools sheet under **Pro**.

## Gallery viewer & editor
Tap any card in the collection carousel (or the "Most viewed" image) to open it full-screen.
- Swipe between photos, **pinch** or **double-tap** to zoom, pan when zoomed, tap to hide the controls.
- **Share** from the top bar. Videos show a play button and open in your player.
- **Edit**: Filters, Adjust (brightness, contrast, saturation, warmth), Crop (rotate, flip, 1:1 / 4:3 / 3:4 / 16:9 / 9:16).
  Press and hold the preview to compare with the original. **Save copy** keeps the original untouched.

## Gestures
| Gesture | Action |
|---|---|
| Tap preview | Focus |
| Pinch | Zoom |
| Double-tap preview | Flip camera |
| Tap pill | Cycle focal length |
| Tap dots | Open tools sheet |
| Long-press dots | Change shutter style |
| Drag dark dock up/down | Expand / collapse camera roll |

## Haptics
`Haptics.kt` – tick (zoom detents, focus), click (buttons), press (finger on shutter),
heavy click (photo saved), double click (style change / flip / error).

## Build the APK
**GitHub (no setup):** push to `main` → Actions → *Build APK* → download `Mate-apk`.
Push a tag like `v1.0.0` and the APK is attached to a GitHub Release automatically.

**Locally:** JDK 17 + Android SDK, then `gradle assembleDebug`
→ `app/build/outputs/apk/debug/Mate-debug.apk`.
(Optional wrapper: `gradle wrapper --gradle-version 8.9`.)

Photos are saved to `Pictures/Mate`. Min Android 10 (API 29).
