# Theme

Files: `ui/theme/Color.kt`, `Theme.kt`, `Type.kt`

## `SyaravinTheme` — `Theme.kt`

Material 3 theme applied in `VinScannerActivity.setContent`. Supports:
- Dynamic color on Android 12+ (`Build.VERSION.SDK_INT >= S`)
- Dark / light system setting
- Host-app typography injection

```kotlin
MaterialTheme(
    colorScheme = ...,
    typography = VinScanner.typographyOverride ?: Typography,
    content = content
)
```

`VinScanner.typographyOverride` is `@Volatile` so writes from the host app are immediately visible on the next recomposition.

To inject the host app's fonts:
```kotlin
// In Application.onCreate() or before launching the scanner
VinScanner.setTypography(MyAppTypography)
```

## Color tokens — `Color.kt`

Base palette (Material purple/pink defaults — rarely used directly):

```
Purple80 / PurpleGrey80 / Pink80   ← dark scheme
Purple40 / PurpleGrey40 / Pink40   ← light scheme
```

ROI feedback colors (used in `ScannerScreen` border animation):

| Token | Hex | Meaning |
|-------|-----|---------|
| `RoiNeutralBorder` | `#FFFFFF` | White — scanning, boxes detected |
| `RoiValidBorder` | `#4AAF57` | Green — valid VIN confirmed |
| `RoiInvalidBorder` | `#F75555` | Red — no boxes detected |

`ScannerScreen` maps `RoiBorderState` → color token → `animateColorAsState(tween(250ms))` for smooth transitions.

`VinResultSheetContent` has hardcoded colors for the action buttons:
- Confirmed: `#0D0DB5` (blue)
- Scan Again: `#EC6234` (orange)

## Typography — `Type.kt`

Library default: `FontFamily.Default` (`bodyLarge` defined; other styles use Material defaults). The host app overrides this entirely via `VinScanner.setTypography()`.

## XML theme — `res/values/themes.xml`

```xml
<style name="Theme.Syaravin" parent="android:Theme.Material.Light.NoActionBar" />
```

Applied to `VinScannerActivity` in `AndroidManifest.xml`. Keeps the XML layer minimal; all visual theming happens through Compose's `SyaravinTheme`.
