# Camera

Files: `data/datasource/camera/CameraDataSource.kt`, `CameraDataSourceImpl.kt`  
UI binding: `presentation/components/CameraPreview.kt`

## `CameraDataSource` interface

```kotlin
fun startCamera(): Flow<ImageProxy>   // stub — see note below
fun stopCamera()                      // stub
fun imageToBitmap(imageProxy: ImageProxy): Bitmap   // live
```

`startCamera()` returns an empty `callbackFlow` in `CameraDataSourceImpl`. The actual CameraX `ImageAnalysis` binding happens in `CameraPreview.kt`. Read [architecture.md](architecture.md) § "Repository Seam" for why.

## `imageToBitmap`

Supports `YUV_420_888`, `NV21`, `NV16`. Throws `IllegalArgumentException` on other formats.

### Primary path — Direct YUV→RGB (`convertYuvToBitmapDirect`)

ITU-R BT.601 per-pixel conversion:
```
Y' = Y - 16
R = clamp(1.164·Y' + 1.596·(V-128))
G = clamp(1.164·Y' - 0.392·(U-128) - 0.813·(V-128))
B = clamp(1.164·Y' + 2.017·(U-128))
```

Handles `pixelStride` and `rowStride` from `imageProxy.planes[]` correctly for both packed and planar UV layouts.

Why direct conversion: JPEG compression introduces blocking artifacts at text edges. TFLite YOLO and ML Kit OCR are sensitive to this — direct RGB preserves full signal quality for the AI pipeline.

### Fallback path — JPEG (`convertYuvToBitmapViaJpeg`)

Used only if direct conversion throws. Constructs NV21 → `YuvImage.compressToJpeg(85%)` → `BitmapFactory.decodeByteArray`. Same rotation is applied.

### Rotation

Both paths call `rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)`. If `rotationDegrees == 0`, returns the original bitmap unchanged (no copy).

## `CameraPreview` composable

`presentation/components/CameraPreview.kt`

Wraps CameraX `PreviewView` in `AndroidView`.

### Generation counter (`AtomicLong activeGeneration`)

CameraX provider futures resolve asynchronously. Without a guard, a stale callback arriving after `onDispose` would bind the camera to a dead lifecycle. The counter is incremented on every bind/release cycle. Each async callback captures `expectedGeneration` at call time and checks `generationRef.get() != expectedGeneration` — if stale, returns early.

### Lifecycle binding

`DisposableEffectWithLifecycle` (from `Extensions.kt`) binds on `ON_START` and releases on `ON_STOP`. A plain `DisposableEffect` also releases on `onDispose`. This handles:
- Foreground/background (ON_START/ON_STOP)
- Activity destruction (onDispose)

`bindCameraUseCases` sequence:
1. Get `ProcessCameraProvider` future
2. On resolve: unbind `preview` + `imageAnalyzer`, set `preview.setSurfaceProvider(previewView.surfaceProvider)`, bind both to lifecycle
3. Set up tap-to-focus: `FocusMeteringAction` at touch point, 3-second auto-cancel

`releaseCameraUseCases`: clears surface provider and touch listener, then unbinds in async callback.

### Performance

`PreviewView.implementationMode = PERFORMANCE` — uses `SurfaceView` backend for lower latency preview rendering.
