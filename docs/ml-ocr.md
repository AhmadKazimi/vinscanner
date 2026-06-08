# ML OCR (TextExtractor)

Files: `data/datasource/ml/TextExtractor.kt`, `TextExtractorImpl.kt`

## Interface

```kotlin
suspend fun extractText(bitmap: Bitmap, boundingBox: BoundingBox): String?
suspend fun extractAllText(bitmap: Bitmap): List<String>
suspend fun extractAllTextWithBounds(bitmap: Bitmap): List<TextWithBounds>
```

`TextWithBounds` pairs a text string with its normalized `BoundingBox`.

`TextExtractorImpl` implements `Closeable` — call `close()` to release the ML Kit recognizer and cancel background coroutines.

## ML Kit recognizer

```kotlin
private val recogniser by lazy {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
}
```

Lazy — created on first access. Thread-safe for concurrent calls. Latin script only.

## Google Play Services guard

ML Kit requires Play Services. At construction, the class checks:
1. `GoogleApiAvailability.isGooglePlayServicesAvailable(context) == SUCCESS`
2. `Class.forName("com.google.mlkit.vision.text.TextRecognition")` doesn't throw

If either fails: `skipOcrForSession = true`, a background `ModuleInstall` request is issued. On subsequent frames, `getRecognizerOrNull()` re-checks and re-enables OCR if Play Services are now ready.

`getRecognizerOrNull()` returns `null` when OCR should be skipped — callers handle this gracefully (return empty/null).

## `extractText` (single region)

1. `toPixelRect(bitmap, boundingBox)` — converts normalized coords to pixel `Rect`, clamped to bitmap bounds
2. `ensureMinimumSize(rect, ...)` — expands to at least 32×32 px (ML Kit minimum), padding equally in all directions
3. `Bitmap.createBitmap(bitmap, left, top, width, height)` — crops region
4. `detectRotation(cropped)` — heuristic:
   - aspect ratio > 1.5 → 0° (wide, normal VIN orientation)
   - aspect ratio < 0.67 → 270° (tall portrait, rotated VIN)
   - otherwise → 0°
5. `InputImage.fromBitmap(cropped, rotationDegrees)`
6. `recogniser.process(image).await()` — suspends on ML Kit Task
7. Returns `result.text` if non-blank, else `null`

## `extractAllText`

Full-bitmap OCR. Returns all lines as `List<String>` (one string per `TextBlock.Line`).

## `extractAllTextWithBounds`

Same as `extractAllText` but returns `List<TextWithBounds>` with each line's bounding box normalized to `[0,1]`.

Note: ML Kit doesn't provide per-character confidence — `BoundingBox.confidence = 1.0f` for all OCR results.

## Background module install

`requestMlKitWarmupInBackground()` — guarded by `AtomicBoolean warmupRequested`. Uses `ModuleInstall.getClient(context).installModules(request).await()` on `backgroundScope` (SupervisorJob + Dispatchers.IO). If Play Services later become available, `skipOcrForSession` is cleared on the next frame.

## Threading

All public methods use `withContext(Dispatchers.Default)`. The background module install scope uses `Dispatchers.IO`. Never call from main thread without `withContext`.
