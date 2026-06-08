# Docs Index

**Read this file first before any modification.**
Then read only the files whose topic matches your current task — do not read everything.

---

## Topic files

| File | Covers | Read when you are touching... |
|------|--------|-------------------------------|
| [architecture.md](architecture.md) | Layer boundaries, data flow, dependency direction, repository seam, key design decisions | Any cross-layer change; understanding how parts connect |
| [public-api.md](public-api.md) | `VinScanner`, `VinScannerContract`, `VinScanResult`, `VinScannerActivity`, `VinNumber` | Host-facing API; Activity result flow; `VinNumber` shape |
| [di.md](di.md) | `VinScannerDependencies`, singleton vs factory lifecycle, TFLite delegate setup, adding dependencies | Adding/removing dependencies; changing object lifecycles |
| [ml-detection.md](ml-detection.md) | `VinDetectorImpl`: YOLO model, letterbox preprocessing, tensor parsing, NMS, warmup, output buffer | ML model, detection thresholds, bounding box coordinates |
| [ml-ocr.md](ml-ocr.md) | `TextExtractorImpl`: ML Kit OCR, Play Services guard, rotation heuristic, module install | OCR, text extraction, ML Kit integration |
| [validation.md](validation.md) | `VinValidatorImpl`: 7-stage pipeline, ISO 3779, OCR corrections, ambiguous-char permutations | VIN validation rules, checksum, `cleanVin`, error messages |
| [camera.md](camera.md) | `CameraDataSourceImpl`: YUV→RGB conversion, JPEG fallback; `CameraPreview` lifecycle, generation counter | Camera frames, bitmap conversion, camera binding/unbinding |
| [scanner-screen.md](scanner-screen.md) | `ScannerScreen`: init order, frame loop, `processImage` stages, ROI crop, auto-confirm, manual entry | The main scanning loop, ROI logic, per-frame processing |
| [viewmodel-state.md](viewmodel-state.md) | `ScannerViewModel`, `ScannerState`, `ScannerEvent`, bitmap memory management | ViewModel logic, state mutations, bitmap lifecycle in state |
| [ui-components.md](ui-components.md) | All Compose components: `RoiOverlay`, `BoundingBoxOverlay`, `VinResultDialog`, input fields | Any UI component change |
| [utils.md](utils.md) | `ImagePreprocessor`, `RoiConfig`, `ScannerPerfConfig`, `ThermalManager`, `VinDecoder`, `SLog` | Utility classes, ROI coordinates, perf config, logging |
| [theme.md](theme.md) | `SyaravinTheme`, color tokens, ROI border colors, typography injection | Theme, colors, host-app font override |

---

## Quick-reference: key constants

| Constant | Value | Location |
|----------|-------|----------|
| TFLite input size | 640×640 | `VinDetectorImpl.MODEL_INPUT_SIZE` |
| Confidence threshold | 0.25 | `VinDetectorImpl.DEFAULT_CONF_THRESHOLD` |
| NMS IoU threshold | 0.45 | `VinDetectorImpl.NMS_IOU_THRESHOLD` |
| Frame interval | 500 ms | `ScannerPerfConfig.inferenceIntervalMs` |
| Camera resolution | 540×960 | `ScannerPerfConfig.imageAnalysisWidth/Height` |
| ROI (normalized) | left=0.04, top=0.44, right=0.96, bottom=0.56 | `RoiConfig.roi` |
| VIN length | 17 | `VinNumber.VIN_LENGTH` |
| Invalid VIN chars | I, O, Q | `VinNumber.INVALID_CHARACTERS` |
| Min digit count | 5 | `VinValidatorImpl` stage 6 |
| Max thermal rate | 3 fps / 200 ms avg | `ThermalManager` |

---

## Package map

```
com.syarah.vinscanner
├── VinScanner / VinScannerActivity / VinScannerContract / VinScanResult   ← public API
├── domain.model          BoundingBox, VinNumber
├── domain.repository     VinScannerRepository (interface)
├── domain.usecase        DetectVin, ExtractText, ValidateVin
├── data.model            DetectionResult, VinValidationResult, VinInfo
├── data.datasource.camera    CameraDataSource(Impl)
├── data.datasource.ml        VinDetector(Impl), TextExtractor(Impl)
├── data.datasource.validator VinValidator(Impl)
├── data.repository       VinScannerRepositoryImpl
├── di                    VinScannerDependencies
├── presentation.scanner  ScannerScreen, ScannerViewModel, ScannerState
├── presentation.components   UI components
├── ui.theme              SyaravinTheme, Color, Type
└── util                  Extensions, ImagePreprocessor, RoiConfig, ScannerPerfConfig,
                          SLog, ThermalManager, VinDecoder
```

---

## Important: what is NOT in the library

- **Koin** — the library uses manual DI (`VinScannerDependencies`). The sample app uses Koin.
- **The repository flow is a stub** — `startScanning()` never emits. `ScannerScreen` drives the frame loop directly. See [architecture.md](architecture.md).
