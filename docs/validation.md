# VIN Validation

Files: `data/datasource/validator/VinValidator.kt`, `VinValidatorImpl.kt`  
Tests: `src/test/.../VinValidatorImplTest.kt`

## Interface

```kotlin
fun validate(vin: String): VinValidationResult   // synchronous, no suspend
fun cleanVin(vin: String): String
```

Both are synchronous — validation is pure string computation.

## `VinValidationResult`

```kotlin
data class VinValidationResult(
    val isValid: Boolean,
    val errorMessage: String?,
    val checksumValid: Boolean,
    val formatValid: Boolean,
    val wasTrimmed: Boolean      // leading/trailing chars were stripped
)
```

## `validate` pipeline (7 stages)

### Stage 1 — Strip leading VIN label
Pattern: `(?i)^\s*VIN(?:\s*(?:NUMBER|NO|#))?\s*[:#=–—\-]?\s*`

Handles: `"VIN:"`, `"VIN NUMBER:"`, `"vin no -"`, `"VIN#"`.

Must run **before** OCR corrections — otherwise `"VIN"` becomes `"V1N"` (I→1) and the regex fails.

### Stage 2 — OCR error correction

```
I/i → 1    O/o → 0    Q/q → 0    l → 1
| → 1      ! → 1      Ø → 0      ° → 0
lowercase a-z → uppercase (except i,o,q,l handled above)
```

### Stage 3 — Extract VIN (`extractVin` → `Pair<String?, Boolean>`)

1. `trim().uppercase()`; strip remaining VIN label prefix
2. `dropWhile { not [A-Z0-9] }` from start; `dropLastWhile { not [A-Z0-9] }` from end → `wasTrimmed`
3. If **any** non-`[A-Z0-9]` character remains in the middle → return `null` (e.g., `"ERA:PPSNAE..."`)
4. Apply `[A-HJ-NPR-Z0-9]{17}` regex → first match is the candidate VIN

Middle characters like colons, spaces, hyphens, asterisks are rejected (strict mode). Leading/trailing non-alphanum is trimmed (`wasTrimmed=true`).

### Stage 4 — Length
Must be exactly 17 characters. Error: `validation_wrong_length`.

### Stage 5 — Invalid character check
No `I`, `O`, or `Q` (should be gone after stage 2, but defensive). Error: `validation_contains_invalid_chars`.

### Stage 6 — Digit count heuristic
At least 5 digits required. A string with fewer digits is almost certainly misread text. Error: `validation_insufficient_digits`.

### Stage 7 — ISO 3779 checksum with permutations

**Checksum algorithm:**
```
value[char] from TRANSLITERATION table
  (A=1,B=2,C=3,D=4,E=5,F=6,G=7,H=8,J=1,K=2,L=3,M=4,N=5,P=7,R=9,
   S=2,T=3,U=4,V=5,W=6,X=7,Y=8,Z=9, 0=0..9=9)
WEIGHTS = [8,7,6,5,4,3,2,10,0,9,8,7,6,5,4,3,2]
sum = Σ(value[i] × WEIGHTS[i])   (position 8 has weight 0)
remainder = sum % 11
checkDigit = 'X' if remainder==10 else digit(remainder)
valid if vin[8] == checkDigit
```

**Permutation tolerance:** OCR commonly misreads:
```
S ↔ 5,   Z ↔ 2,   B ↔ 8,   A ↔ 4,   G ↔ 6
```
BFS explores all single-position swaps (max `maxChanges=1`). Uses `seenVins` set to prevent cycles.

**Soft validation:** If even permutation search fails, returns `isValid=true, checksumValid=false`. A format-valid VIN with a bad checksum is almost always an OCR transcription error, not a fake VIN.

## `cleanVin`

Runs stages 1–3 only. Returns extracted VIN string or `""`. Used by `ScannerScreen` to normalize OCR output before calling `validate`.

## Adding validation rules

1. Add the check between the appropriate existing stage
2. Add a localized error string to `res/values/strings.xml` (and `values-ar/strings.xml`)
3. Add a test in `VinValidatorImplTest` covering both pass and fail cases

## Localized error strings

| String key | Meaning |
|------------|---------|
| `validation_invalid_chars_or_no_valid_vin` | Stage 3 failure |
| `validation_wrong_length` | Stage 4 failure (has `%1$d` placeholder) |
| `validation_contains_invalid_chars` | Stage 5 failure |
| `validation_insufficient_digits` | Stage 6 failure |
| `validation_checksum_accepted` | Stage 7 soft accept |
