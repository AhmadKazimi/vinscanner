# UI Components

All in `presentation/components/`. All are `internal`.

## `CameraPreview` — `CameraPreview.kt`

See [camera.md](camera.md) for full details.

## `RoiOverlay` — `RoiOverlay.kt`

Canvas composable. Receives `roiBox: BoundingBox` (normalized) defaulting to `RoiConfig.roi`.

Draws:
1. **Scrim** — 4 `drawRect` calls for top/bottom/left/right areas outside ROI (55% black alpha)
2. **Border** — `drawRect(style=Stroke)` around the ROI cutout
3. **Corner accents** — 8 `drawLine` calls making L-shapes at each corner (default 24dp length)

Border color and width are configurable parameters; `ScannerScreen` passes an animated `roiBorderColor`.

## `BoundingBoxOverlay` — `BoundingBoxOverlay.kt`

Canvas composable. For each `BoundingBox` in `boundingBoxes`:
1. `drawRect(style=Stroke)` at `(box.left × width, box.top × height, ...)` using theme's primary color
2. If `box.confidence > 0.25`: draws `"${confidence%}%"` text at the top-left corner via `nativeCanvas.drawText`

Logs overlay render time every 30 frames via `ScannerPerfConfig.overlayTiming`.

## `VinResultDialog` / `VinResultSheetContent` — `VinResultDialog.kt`

`VinResultDialog` is a thin `Dialog` wrapper around `VinResultSheetContent`.

`VinResultSheetContent` shows:
- "VIN Detected" header + green checkmark
- VIN number card (non-editable display)
- Cropped VIN image card — `ImagePreprocessor.downscaleForDisplay()` applied before display; shown only when `vinNumber.croppedImage != null`
- Car information card — `VinDecoder.decode(vin)` via `derivedStateOf`; shown when result is non-null
- Bottom action section:
  - **Confirmed** button (blue `#0D0DB5`) — enabled only when `isCurrentVinValid`; passes `vinNumber.copy(value=vin, isValid=...)` to `onConfirm`
  - **Scan Again** button (orange `#EC6234`) — calls `onRetry`

`vin` is mutable local state initialized from `vinNumber.value`. `isCurrentVinValid` re-runs `vinValidator.validate(vin).isValid` on every change via `mutableStateOf`.

`vinDecoder` and `vinValidator` are injected via default parameters from `VinScannerDependencies.get()` — override in tests.

## `VinEditBar` — `VinEditBar.kt`

`Surface` (large shape, 4dp tonal elevation) containing:
- `VinInputField` (17-cell grid)
- Row with: Clear `IconButton` (✕) + Done `Button` (✓ + text)

## `VinInputField` — `VinInputField.kt`

17 individual `BasicTextField` boxes, one per VIN character.

- Auto-advances focus via `FocusRequester` array: on character typed, `focusRequesters[index+1].requestFocus()`
- Forces uppercase via `it.text.uppercase()`
- Visual: 1dp primary-colored border, `RoundedCornerShape(2dp)`, white text 20sp, centered

## `VinTextField` — `VinTextField.kt`

Single-line text field for the result dialog.

Features:
- Monospace 28sp, letter spacing 3sp, centered
- **Paste button** (AddCircle icon) — reads from `ClipboardManager`; shown when empty or editing
- **Edit button** (Edit icon) — enters edit mode, requests focus
- Character counter: `"n / 17 characters"` below; turns primary color when `n == 17`
- Border: transparent normally, primary when editing, error when `!isValid`
- Filters to alphanumeric only, max 17 chars
- `LaunchedEffect(isEditing)` requests focus when edit mode activates
