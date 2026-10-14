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

## Gestures
| Gesture | Action |
|---|---|
| Tap preview | Focus |
| Pinch | Zoom |
| Double-tap preview | Flip camera |
| Tap pill | Cycle focal length |
| Tap dots | Change shutter style |
| Long-press dots | Toggle grid |
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
