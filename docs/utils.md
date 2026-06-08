# Utilities

All in `util/`. All are `internal`.

## `ImagePreprocessor` — `ImagePreprocessor.kt`

### `enhanceVinImage(bitmap): Bitmap`
Applies `ColorMatrix` with contrast factor 1.5× and brightness offset +10 on R/G/B channels. Returns enhanced copy. Falls back to original on exception.

### `cropAndEnhance(bitmap, left, top, right, bottom, paddingPercent=0.15f): Bitmap?`
Crops to normalized bounding box with 15% padding on each side (clamped to bitmap bounds), then runs `enhanceVinImage`. Returns `null` on failure.

### `downscaleForDisplay(bitmap, maxDimension=1600, maxPixels=1_500_000): Bitmap`
Scales down maintaining aspect ratio. Only scales down, never up. Applies the stricter of:
- Dimension limit: scale so max(width, height) ≤ 1600
- Pixel count limit: scale so width × height ≤ 1,500,000

Used before storing bitmaps in `ScannerState` to prevent OOM from holding full-resolution camera bitmaps in Compose state.

## `RoiConfig` — `RoiConfig.kt`

```kotlin
val analyzedImageAspectRatio = 9f / 16f   // 540×960 portrait
val roi = BoundingBox(left=0.04, top=0.44, right=0.96, bottom=0.56, confidence=1f)
```

ROI is a horizontal strip: 4% padding on each side (≈16dp at 540px), covering the vertical center 12% of frame (rows 44%–56%). VINs on vehicles appear as horizontal text; this strip focuses detection on the most likely position and reduces noise from sky, interior, and body panels.

To change the ROI: update `left`, `top`, `right`, `bottom` here. The overlay, detection, and coordinate mapping in `ScannerScreen.processImage` all read from this object.

## `ScannerPerfConfig` — `ScannerPerfConfig.kt`

System-property-configurable performance settings. Values are read at class initialization (JVM startup), not per-call.

| System property | Default | Effect |
|----------------|---------|--------|
| `syaravin.perf.logs` | `true` | Enable perf log output |
| `syaravin.perf.inference.interval.ms` | `500` | Min ms between processed frames |
| `syaravin.perf.camera.analysis.width` | `540` | Camera resolution W |
| `syaravin.perf.camera.analysis.height` | `960` | Camera resolution H |
| `syaravin.perf.tflite.threads` | `4` | CPU thread count (allowed: 1,2,4,6) |
| `syaravin.perf.tflite.delegate` | `"gpu"` | Delegate: gpu / nnapi / cpu / xnnpack |
| `syaravin.perf.tflite.xnnpack` | auto | Force XNNPack on/off |

Override via `adb shell setprop syaravin.perf.tflite.delegate cpu` (no rebuild needed).

`FrameTimingLogger` — logs every 30 frames: FPS, avg end-to-end latency ms, dropped frame count, per-stage breakdown.  
`ThrottledDurationLogger` — logs a named duration every N events.

## `ThermalManager` — `ThermalManager.kt`

Monitors processing rate and average time over 60-second rolling windows.

`shouldThrottle()` returns `true` if:
- Processing rate > 3.0 fps, OR
- Average processing time > 200ms

Resets counters every 60 seconds. Currently not called in the hot path (500ms interval already caps rate at ~2fps), but available as a safety valve. Call `recordProcessing(processingTimeMs)` after each frame.

## `VinDecoder` — `VinDecoder.kt`

Loads `assets/vin_data.json` lazily via `kotlinx.serialization`.

JSON structure:
```json
{
  "wmi": { "1HG": { "manufacturer": "Honda", "country": "USA" } },
  "model_year": { "K": 2019, "L": 2020 },
  "assembly_plant": { "Honda": { "1": "Marysville, Ohio" } }
}
```

`decode(vin: String): VinInfo?` — returns `null` if `vin.length != 17`. Extracts:
- `vin[0..2]` (WMI) → manufacturer + country
- `vin[9]` → model year
- `vin[10]` + manufacturer → assembly plant

## `SLog` — `SLog.kt`

Structured logger. Single Logcat tag: `"SYARAHVIN"`.

Default minimum level: **WARN** — `DEBUG`/`INFO` are suppressed unless host sets:
```bash
adb shell setprop log.tag.SYARAHVIN DEBUG
```

Emits OpenTelemetry-style JSON:
```json
{"timestamp":"...","severityText":"WARN","body":"[module=VinDetectorImpl] msg",
 "attributes":{"library":"Syaravin","module":"...","thread.name":"..."}}
```

Module name is auto-detected by walking `Throwable().stackTrace` to find the first non-`SLog` library class. Do not call via reflection or from a lambda — the stack walk will find the wrong class.

API: `SLog.v/d/i/w/e(tag, message)` and `SLog.w/e(tag, message, throwable)`.

## `Extensions.kt`

`Context.showToast(message, duration)` — utility.

`DisposableEffectWithLifecycle(lifecycleOwner, onCreate, onStart, onStop, onDestroy, onResume, onPause)` — attaches a `LifecycleEventObserver` and removes it on `onDispose`. Used by `CameraPreview` for bind/release on foreground transitions.
