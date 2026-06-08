# Dependency Injection

File: `di/VinScannerDependencies.kt`

## Initialization

`VinScannerDependencies` is a thread-safe singleton using double-checked locking:

```kotlin
VinScannerDependencies.initialize(appContext)  // called in VinScannerActivity.onCreate
VinScannerDependencies.get()                   // throws if not initialized
VinScannerDependencies.release()               // cleanup (closes ML Kit, nulls instance)
```

`initialize` is idempotent — safe to call multiple times. It stores `appContext.applicationContext` to avoid Activity leaks.

## `DependencyContainer`

### Singletons (lazy — created on first access)

| Name | Type | Notes |
|------|------|-------|
| `interpreter` | `Interpreter` | TFLite model; GPU/NNAPI/CPU delegate |
| `vinDetector` | `VinDetectorImpl` | wraps interpreter |
| `textExtractor` | `TextExtractorImpl` | ML Kit recognizer; is `Closeable` |
| `vinValidator` | `VinValidatorImpl` | stateless; needs `Context` for strings |
| `vinDecoder` | `VinDecoder` | loads `vin_data.json` once |
| `cameraDataSource` | `CameraDataSourceImpl` | stateless converter |

All singletons are `by lazy` — thread-safe via Kotlin's `LazyThreadSafetyMode.SYNCHRONIZED` default.

### TFLite delegate setup (inside `interpreter` lazy block)

Order of preference, controlled by `ScannerPerfConfig.delegateMode` (system property `syaravin.perf.tflite.delegate`, default `"gpu"`):

1. **GPU** — checks `CompatibilityList.isDelegateSupportedOnThisDevice`, uses `bestOptionsForThisDevice`
2. **NNAPI** — adds `NnApiDelegate()`
3. **CPU/XNNPACK** — uses `setNumThreads(4)` + `setUseXNNPACK(true)`

Model loaded as memory-mapped `ByteBuffer` from `assets/best_float32.tflite`. `allocateTensors()` called after options are set.

### Factory methods (new instance every call)

| Method | Returns | Lifecycle |
|--------|---------|-----------|
| `createExecutor()` | `ExecutorService` (single-thread) | per screen; shut down in `DisposableEffect` |
| `createCameraSelector()` | `CameraSelector.DEFAULT_BACK_CAMERA` | per screen |
| `createPreview()` | `Preview` | per screen |
| `createImageAnalysis()` | `ImageAnalysis` | per screen; 540×960, `STRATEGY_KEEP_ONLY_LATEST` |
| `createRepository()` | `VinScannerRepositoryImpl` | per ViewModel |
| `createScannerViewModel()` | `ScannerViewModel` | via `ScannerViewModelFactory` |

`createImageAnalysis()` uses `ResolutionSelector` targeting 540×960, `ROTATION_0`, `STRATEGY_KEEP_ONLY_LATEST` (drops frames, never queues).

### `warmUpScannerDependencies()`

Accesses `vinDetector`, `textExtractor`, `vinValidator`, `cameraDataSource` lazy properties on `Dispatchers.Default` to force their initialization before the first frame. Called from `ScannerScreen` via `LaunchedEffect`.

### `release()`

Closes `textExtractor` (which closes the ML Kit recognizer and cancels its background scope). Nulls `instance`.

## Adding a new dependency

1. Add a `val foo: FooImpl by lazy { FooImpl(appContext) }` in `DependencyContainer`
2. If it has a factory variant, add `fun createFoo(): Foo`
3. If it needs cleanup, add `runCatching { (foo as? Closeable)?.close() }` in `release()`
4. Wire it into `createScannerViewModel()` or whichever factory method needs it
5. Read [architecture.md](architecture.md) for layer rules before deciding where to pass it
