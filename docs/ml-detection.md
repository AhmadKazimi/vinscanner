# ML Detection (VinDetector)

Files: `data/datasource/ml/VinDetector.kt`, `VinDetectorImpl.kt`

## Interface

```kotlin
suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float = 0.25f): DetectionResult
fun preprocessImage(bitmap: Bitmap): Bitmap
```

`DetectionResult` wraps `List<BoundingBox>` and `processingTimeMs`.

## Model

File: `assets/best_float32.tflite`  
Type: single-class YOLO-style object detector  
Input: 640×640 RGB float32  
Output: `[1, 8400, 6]` or `[1, 6, 8400]` — both formats supported at runtime

## Preprocessing (`preprocessImage`)

**Letterbox** — scale uniformly so the longer side fits in 640 pixels, then center-pad with black to 640×640:

```
scaleFactor = min(640/srcW, 640/srcH)
scaledW = srcW * scaleFactor
scaledH = srcH * scaleFactor
padLeft = (640 - scaledW) / 2
padTop  = (640 - scaledH) / 2
```

`padLeft`/`padTop` are saved as locals in `detect()` for un-letterboxing after inference. Without letterboxing, portrait frames get squashed and box coordinates are wrong.

## Input buffer

Pre-allocated once in the constructor:
```kotlin
ByteBuffer.allocateDirect(640 * 640 * 3 * 4)  // 3 channels × 4 bytes float32
```
`convertBitmapToByteBuffer`: `getPixels()` then divide each R/G/B channel by 255f.

## Output parsing

The output tensor can be oriented in two ways:
- **candidates-first** `[1, 8400, props]` — most common
- **properties-first** `[1, props, 8400]`

Detection at runtime: check which dimension is in `{5, 6, 84, 85}` (known properties counts). If neither matches, use the smaller dimension as properties. A `getProp(candidateIndex, propIndex)` lambda abstracts the orientation.

Per-candidate scoring:
```
objectness = getProp(i, 4)            // position 4 in properties
classScore = max(getProp(i, 5..end))  // if propertiesCount > 5
conf = objectness × classScore
```

Skip candidates below `max(confidenceThreshold, DEFAULT_CONF_THRESHOLD=0.25)`.

## Coordinate un-letterboxing

```
leftContent  = (leftPxModel  - padLeft) / scaledWidth    clamped [0,1]
topContent   = (topPxModel   - padTop)  / scaledHeight   clamped [0,1]
rightContent = (rightPxModel - padLeft) / scaledWidth    clamped [0,1]
bottomContent= (bottomPxModel- padTop)  / scaledHeight   clamped [0,1]
```

Boxes where `right <= left` or `bottom <= top` are discarded.

## NMS

Greedy single-class NMS. Sort by descending confidence. For each kept box, remove all remaining boxes with IoU > 0.45.

`computeIoU`: intersection area / union area. Returns 0 if union ≤ 0.

## Warmup

`maybeWarmupInterpreter()` — runs 3 dummy inference passes on first call (guarded by `AtomicBoolean`). Initializes GPU shader compilation and XNNPACK JIT to prevent first-frame latency spikes.

## Output buffer caching

`getOrCreateOutputBuffer(dimA, dimB)` — caches `Array<Array<FloatArray>>` and only reallocates if tensor dimensions change. Avoids GC pressure every inference.

## Constants

```
MODEL_INPUT_SIZE = 640
DEFAULT_CONF_THRESHOLD = 0.25f
NMS_IOU_THRESHOLD = 0.45f
```

## Changing the model

1. Replace `assets/best_float32.tflite`
2. If input size changes: update `MODEL_INPUT_SIZE`
3. If output format changes: verify the orientation-detection logic handles the new shape
4. Confirm `preprocessImage` letterbox still produces correct `padLeft`/`padTop` for un-letterboxing
5. Run a warmup pass manually to confirm `allocateTensors()` succeeds
