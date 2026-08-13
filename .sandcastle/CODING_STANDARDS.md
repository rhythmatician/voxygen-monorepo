# Coding Standards

<!-- Customize this file with your project's coding standards.
     The reviewer agent loads it during code review via @.sandcastle/CODING_STANDARDS.md
     so these standards are enforced during review without costing tokens during implementation. -->

## Style

### Trust the Type System

**Rule:** When a variable has a concrete type annotation, treat that annotation as the source of truth. Do not write runtime checks for attributes, methods, or properties that are already guaranteed by the declared type.

**Decision tree — accessing an attribute, or constructing a test object:**

```
Need data from an object?
│
├── Do I know the declared type?
│   │
│   ├── NO  → Improve the typing first. Unknown type = type problem, not runtime problem.
│   │
│   └── YES →
│       ├── Concrete type  → Direct attribute access.
│       └── Union type     → Type narrowing (instanceof/pattern matching), then direct access.
│
└── Need an object for a test?
    ├── Use a real domain type (record, data class, TypedDict, named type).
    ├── Use a typed fake (record/data class with the minimal fields needed).
    ├── Use Mock(spec=Interface) ONLY for boundary interfaces (repositories, services).
    └── Never use untyped mocks for domain data objects.
        Any attribute set on an untyped mock is invisible to static analysis.
```

**Bad — all three are the same mistake, wearing different clothes:**

```java
// Java: For chunk: Chunk — the type already guarantees .getBiome() exists.
// All three bypass static analysis for no benefit.

Biome process(Chunk chunk) {
    return chunk.getBiome();         // Direct access - correct
}

// Anti-patterns that bypass static analysis:
Biome process(Chunk chunk) {
    return Optional.ofNullable(chunk.getBiome()).orElse(Biome.PLAINS);  // Unnecessary
}

Biome process(Chunk chunk) {
    try {
        return chunk.getBiome();
    } catch (NullPointerException e) {  // Type-checker blind
        return Biome.PLAINS;
    }
}
```

```python
# Python: For sample: TrainingSample — the type already guarantees .voxel_data exists.
# All three bypass static analysis for no benefit.

def process(sample: TrainingSample) -> VoxelData:
    return sample.voxel_data                         # Direct access - correct

# Anti-patterns that bypass static analysis:
def process(sample: TrainingSample) -> VoxelData:
    return getattr(sample, "voxel_data", None)       # type-checker blind

def process(sample: TrainingSample) -> VoxelData:
    if hasattr(sample, "voxel_data"):                # type-checker blind
        return sample.voxel_data
    return None

def process(sample: TrainingSample) -> VoxelData:
    try:                                             # type-checker blind
        return sample.voxel_data
    except AttributeError:
        return None
```

**Good — the annotation is the guarantee:**

```java
Biome process(Chunk chunk) {
    return chunk.getBiome();  // direct; compiler and IDE both verify this
}
```

```python
def process(sample: TrainingSample) -> VoxelData:
    return sample.voxel_data      # direct; Pylance and mypy both verify this
```

**Good — Union narrowed with pattern matching / isinstance:**

```java
Biome process(Chunk | Region item) {
    if (item instanceof Chunk chunk) {
        return chunk.getBiome();
    }
    return item.getDominantBiome();
}
```

```python
def process(item: Chunk | Region) -> Biome:
    if isinstance(item, Chunk):
        return item.biome
    return item.dominant_biome
```

**Impact:** Enforced globally. When the type annotation is correct, unnecessary null checks, Optional wrapping, or try/catch for attribute access should not appear on statically-typed objects.

---

### Ban Dynamic Lookups with Hardcoded Strings

**Rule:** Do not use reflection (`getField`, `getMethod`) with hardcoded string literals for static attribute access. Use direct attribute/method access, and strict type narrowing (`instanceof`, pattern matching) when necessary.

**Reasoning:**

* **Type-Checker Blindness:** Static analysis tools cannot track reflective lookups. Bypassing them destroys real-time IDE feedback regarding nullability and exact return types.
* **Broken Refactoring:** Global "Rename Symbol" operations completely ignore strings. Hardcoded reflection calls will silently break when a model property is updated.
* **Fail-Fast Reliability:** Direct access shifts runtime errors to compile-time.

**Bad: Bypassing Static Analysis**

```java
// Compiler cannot verify these lookups; renaming breaks silently

// Anti-pattern: Reflection with string literal
Object value = chunk.getClass().getField("biomeId").get(chunk);

// Anti-pattern: Checking if an object has a field when we already know its type
if (hasField(chunk, "biomeId")) {
    process(getField(chunk, "biomeId"));
}
```

```python
# Pylance cannot verify these lookups; renaming breaks silently

# Anti-pattern: passing a string literal and None to getattr's 2nd and 3rd inputs
value = getattr(sample, "voxel_data", None)

# Anti-pattern: Checking if an object has an attribute when we already know (based on its type)
if hasattr(sample, "voxel_data"):
    process(sample.voxel_data)

# Anti-pattern: Using exceptions for standard flow control
try:
    value = sample.voxel_data
except AttributeError:
    value = None
```

**Good: Idiomatic Type Narrowing (`instanceof` / pattern matching / isinstance)**

```java
// Compiler instantly knows the exact shape of 'obj'
if (obj instanceof Chunk chunk) {
    // If biomeId is non-nullable on Chunk, no further checks needed.
    process(chunk.getBiomeId());
}
```

```python
# Pylance instantly knows the exact shape of 'obj', including
# whether 'voxel_data' is nullable or guaranteed to exist.
if isinstance(obj, Chunk):
    # If voxel_data is non-nullable on Chunk, no further checks are needed.
    process(obj.voxel_data)
```

**Good: Direct Access & Null Checks (Known Types)**

```java
void calculateDensity(Chunk chunk) {
    // The type is known. Only check for null if the property is explicitly nullable.
    if (chunk.getNoiseValue() != null) {
        return processNoise(chunk.getNoiseValue());
    }
}
```

```python
def calculate_density(chunk: Chunk):
    # The type is known. Only check for None if the property is typed as Optional/Nullable.
    if chunk.noise_value is not None:
        return process_noise(chunk.noise_value)
```

**Exceptions:**
Dynamic lookups (reflection) are strictly reserved for:
* Attribute names originating from external, dynamic inputs (e.g., config file keys, user-defined strings, or raw database column mappings).
* Explicit proxy objects or highly dynamic meta-programming where static access is fundamentally impossible.
* *Requirement:* Any use of reflection must include a comment explaining why direct access could not be used.

---

### Fix Types, Don't Defend Against Them

**Rule:** If code requires reflection, unnecessary null checks, or try/catch to access an attribute on an object whose type is supposedly known, the type is wrong or incomplete. Improve the type — do not add runtime checks.

**Reasoning:**
Runtime attribute probing is a symptom of a type information problem, not a solution to it. Adding defensive checks treats the symptom while leaving the root cause (a weak or missing type annotation) in place.

**When this arises, ask:**
> "Why does this attribute feel uncertain?"

The answer is almost always one of:
* The parameter type is `Object` / `any` → replace with a concrete type.
* The function accepts a base class but needs a subclass attribute → narrow with `instanceof`/pattern matching or refine the parameter type.
* The attribute is genuinely optional → annotate it as `@Nullable` / `Optional<T>` and check for `null`, not catch exceptions.
* The object comes from an untyped third-party library → add a type stub, an interface, or a typed wrapper at the boundary.

**Bad — treating a type problem as a runtime problem:**

```java
// If chunk sometimes doesn't have .biome, that's a type annotation problem.
Biome getBiome(Chunk chunk) {
    Biome biome = Optional.ofNullable(chunk.getBiome()).orElse(Biome.PLAINS);  // Wrong: fix the type
    return biome;
}
```

```python
# If sample sometimes doesn't have .voxel_data, that's a type annotation problem.
def get_voxel_data(sample: TrainingSample) -> VoxelData:
    data = getattr(sample, "voxel_data", None)   # Wrong: fix the type, not the call site
    return data
```

**Good — fix the type:**

```java
// Option A: biome is always present — use it directly.
Biome getBiome(Chunk chunk) {
    return chunk.getBiome();
}

// Option B: biome is genuinely optional — annotate it and check for null.
// (This is a change to the Chunk model, not the call site.)
record Chunk(@Nullable Biome biome) {}

Biome getBiome(Chunk chunk) {
    return chunk.biome() != null ? chunk.biome() : Biome.PLAINS;
}
```

```python
# Option A: voxel_data is always present — use it directly.
def get_voxel_data(sample: TrainingSample) -> VoxelData:
    return sample.voxel_data

# Option B: voxel_data is genuinely optional — annotate it and check for None.
# (This is a change to the TrainingSample model, not the call site.)
@dataclass
class TrainingSample:
    voxel_data: VoxelData | None

def get_voxel_data(sample: TrainingSample) -> VoxelData:
    return sample.voxel_data or DEFAULT_VOXEL_DATA
```

**Impact:** Forward-looking. Whenever you find yourself writing a runtime attribute check, stop and ask whether the type annotation should be improved instead.

---

### Convert `Object` / `any` / `Any` Into a Type Immediately

**Rule:** The top type (`Object` in Java, `any` in TypeScript, `Any` in Python) is permitted only at external boundaries — raw JSON payloads, database row results, third-party untyped returns. It must be converted into a named, concrete type as immediately as possible. The top type must not propagate inward.

**Reasoning:**
The top type effectively disables the type system for everything downstream of it. Once a variable is typed as the top type:
* `obj.attributeThatDoesNotExist` type-checks silently.
* Reflection/property access with arbitrary strings type-checks silently.
* Compiler, IDE, and rename-symbol all stop helping.

The goal is not zero top-type — it is the top type staying at the boundary layer where external data enters the system, and nowhere else.

**The philosophy behind static typing:**
> The purpose of the type system is not to eliminate warnings.
> The purpose of the type system is to make illegal states unrepresentable.

That philosophy leads toward `interface`, `record`, `sealed class`, `Union`, and pattern matching — not toward `Object`, `any`, reflection, or try/catch.

**Bad — top type propagates inward:**

```java
Map<String, Object> loadChunkData(Map<String, Object> raw) {   // Object escapes the boundary
    return raw;
}

void processChunk(Map<String, Object> payload) {               // Object spreads further
    int biomeId = (Integer) payload.get("biome_id");           // no type safety, unchecked cast
}
```

```python
def load_chunk_data(raw: dict[str, Any]) -> dict[str, Any]:   # Any escapes the boundary
    return raw

def process_chunk(payload: dict[str, Any]) -> None:           # Any spreads further
    biome_id = payload["biome_id"]                             # no type safety
```

**Good — convert at the boundary, pass typed objects inward:**

```java
record ChunkData(int biomeId, float[] noiseValues, int lodLevel) {}

ChunkData loadChunkData(Map<String, Object> raw) {      // Object consumed here
    return new ChunkData(
        ((Number) raw.get("biome_id")).intValue(),
        (float[]) raw.get("noise_values"),
        ((Number) raw.get("lod_level")).intValue()
    );
}

void processChunk(ChunkData chunk) {                    // fully typed from here on
    generateTerrain(chunk.biomeId(), chunk.noiseValues(), chunk.lodLevel());
}
```

```python
@dataclass
class ChunkData:
    biome_id: int
    noise_values: list[float]
    lod_level: int

def load_chunk_data(raw: dict[str, Any]) -> ChunkData:      # Any consumed here
    return ChunkData(
        biome_id=int(raw["biome_id"]),
        noise_values=list(raw["noise_values"]),
        lod_level=int(raw["lod_level"]),
    )

def process_chunk(chunk: ChunkData) -> None:                # fully typed from here on
    generate_terrain(chunk.biome_id, chunk.noise_values, chunk.lod_level)
```

**Exceptions:**
The top type may appear at external boundaries. Every such occurrence must include a comment explaining why a concrete type cannot yet be used:

```java
// Object: Minecraft NBT API returns Object; we narrow immediately via parse() before returning.
Object rawTag = nbtCompound.get("biome_data");
```

```python
# Any: PyTorch tensor.numpy() returns Any; we narrow immediately via typed wrapper.
raw_tensor: Any = model_output.numpy()
```

**Impact:** Forward-looking. Existing boundary uses of the top type age out during routine maintenance when the surrounding module is refactored.

---

## Testing

### Prefer Real Types Over Mocks

**Rule:** Prefer real domain instances, records, data classes, and typed fixtures over untyped mocks whenever practical. Use `Mock(spec=Interface)` only for boundary interfaces (repositories, services, external I/O). Never use untyped mocks for domain data objects.

**Reasoning:**
Untyped mock objects are invisible to static analysis. Any attribute you set on one — `mock.setName("Sheet1")` — is unknown to the type system. This means:
* Static analysis cannot catch mismatches between the mock and the real type.
* When the domain object is renamed or restructured, the test continues to pass silently.
* The test verifies nothing about the actual type's shape.

Real typed objects participate in static analysis. A test built from a real `WorksheetContract` will fail at compile time if the type's fields change, surfacing the break immediately.

**Prefer real domain objects:**

```java
// Good — the type participates in static analysis
Chunk chunk = new Chunk(
    Biome.FOREST,
    new float[] {0.1f, 0.2f, 0.3f},
    3
);

// Bad — untyped mock is invisible to compiler; mismatches are never caught
Chunk chunk = mock(Chunk.class);
when(chunk.getBiome()).thenReturn(Biome.FOREST);
```

```python
# Good — the type participates in static analysis
sample = TrainingSample(
    voxel_data=VoxelData(shape=(32, 32, 32), dtype=np.float32),
    biome_id=Biome.FOREST,
    lod_level=3
)

# Bad — MagicMock is invisible to mypy; mismatches are never caught
sample = MagicMock()
sample.voxel_data = np.zeros((32, 32, 32))
sample.biome_id = Biome.FOREST
```

**When you need a simplified object — use a typed fake, not an untyped mock:**

```java
// Good — compiler and IDE understand this shape
record FakeChunk(Biome biome, float[] noiseValues, int lodLevel) {}

// Bad — silent under static analysis
Chunk chunk = mock(Chunk.class);
```

```python
# Good — Pylance and mypy understand this shape
@dataclass
class FakeChunk:
    biome: Biome
    noise_values: list[float]
    lod_level: int

# Bad — silent under static analysis
chunk = MagicMock()
```

Typed fakes should live in the `test-support/` module or as private helpers in the test file. They are not production code; they are explicitly limited in scope.

**Mock boundaries, not data:**

```java
// Good — a repository is a boundary; Mock(spec=...) verifies the interface contract
ChunkRepository repository = mock(ChunkRepository.class);
when(repository.findByCoords(0, 0, 0)).thenReturn(realChunk);

// Bad — a chunk is data, not a boundary
Chunk chunk = mock(Chunk.class);
when(chunk.getBiome()).thenReturn(Biome.FOREST);
when(chunk.getLodLevel()).thenReturn(3);
```

```python
# Good — a repository is a boundary; Mock(spec=...) verifies the interface contract
repository = Mock(spec=IChunkRepository)
repository.find_by_coords.return_value = real_chunk

# Bad — a chunk is data, not a boundary
chunk = Mock()
chunk.biome = Biome.FOREST
chunk.lod_level = 3
```

The distinction: *data* objects should be real typed instances. *Boundary* objects (repositories, services, I/O adapters) may be mocked with `Mock(spec=Interface)` because you are testing behavior at the seam, not the data shape.

**Exceptions:**
Untyped mocks are permissible when:
* Testing call patterns at an I/O boundary (e.g., verifying a repository method is called with specific arguments).
* Working with third-party library objects that have no public constructor.

In these cases, always use `Mock(spec=TargetClass)` rather than bare untyped mocks, so the interface contract is still enforced.

**Impact:** Forward-looking. Applies globally to test files. Existing bare untyped mock usages at data sites should be replaced during routine maintenance.

---

### Unknown Domain Knowledge: Defer to Stub Functions

**Rule:** When requirements, domain rules, validation criteria, classification rules, thresholds, business logic, or other domain-specific knowledge are unclear, ambiguous, or undefined—do not guess or invent. Instead, declare the function signature with an explicit stub that throws `UnsupportedOperationException` (Java) / `NotImplementedError` (Python) / `throw new Error("Not implemented")` (TypeScript).

**Reasoning:**
* **Prevents Speculative Code:** Avoids introducing silent bugs caused by AI (or developers) inventing thresholds, percentages, scoring rules, or validation criteria that contradict actual business requirements.
* **Maintains Searchability:** Stub functions create discoverable gaps. Grep, IDE search, and code review tools can easily locate and track undefined logic.
* **Documents Intent Explicitly:** The code declares what *needs* to be implemented, making it obvious to reviewers that this is deliberate, not accidental.
* **Fails Fast:** Calling code breaks immediately at runtime—not silently during production.

**Bad: Invented / Assumed Domain Rules**

```java
// Anti-pattern: Invented density threshold (who says 0.5?)
Biome classifyTerrainType(float density) {
    if (density > 0.5f) return Biome.MOUNTAINS;
    else if (density > 0.2f) return Biome.HILLS;
    else return Biome.PLAINS;
}

// Anti-pattern: Invented LOD transition distance (100 blocks came from nowhere)
int calculateLodLevel(int distanceFromPlayer) {
    if (distanceFromPlayer > 100) return 4;  // Invented rule
    else if (distanceFromPlayer > 50) return 2;
    return 0;
}
```

```python
# Anti-pattern: Invented noise threshold (who says 0.3?)
def classify_terrain_type(density: float) -> Biome:
    if density > 0.3:
        return Biome.MOUNTAINS
    elif density > 0.1:
        return Biome.HILLS
    else:
        return Biome.PLAINS

# Anti-pattern: Invented learning rate schedule (0.001 came from nowhere)
def get_learning_rate(epoch: int) -> float:
    if epoch > 100:
        return 0.0001  # Invented rule
    return 0.001

# Anti-pattern: Invented voxel resolution (64^3 came from nowhere)
def get_voxel_resolution(lod: int) -> int:
    return 64 >> lod  # Invented formula
```

**Good: Stub Functions with Explicit Gaps**

```java
Biome classifyTerrainType(float density) {
    /**
     * Classify terrain type based on noise density thresholds.
     * Thresholds to be determined by terrain generation spec (see ticket #XXXX).
     */
    throw new UnsupportedOperationException(
        "Terrain classification thresholds not yet defined. " +
        "Awaiting noise design specification."
    );
}

int calculateLodLevel(int distanceFromPlayer) {
    /** Calculate LOD level based on view distance config. */
    throw new UnsupportedOperationException(
        "LOD transition distances not yet extracted from render config. " +
        "See ticket #YYYY."
    );
}

// Calling code—fails immediately if stub is invoked
void generateChunk(ChunkContext ctx) {
    Biome biome = classifyTerrainType(ctx.density());  // Throws UnsupportedOperationException
    // ... rest of logic
}
```

```python
def classify_terrain_type(density: float) -> Biome:
    """
    Classify terrain type based on noise density thresholds.

    Thresholds to be determined by terrain generation spec (see ticket #XXXX).
    """
    raise NotImplementedError(
        "Terrain classification thresholds not yet defined. "
        "Awaiting noise design specification."
    )

def get_learning_rate(epoch: int) -> float:
    """Get learning rate from training schedule."""
    raise NotImplementedError(
        "Learning rate schedule not yet extracted from training config. "
        "See ticket #YYYY."
    )

def get_voxel_resolution(lod: int) -> int:
    """Get voxel resolution for LOD level."""
    raise NotImplementedError(
        "Voxel resolution formula pending verification. "
        "Awaiting LOD system specification."
    )

# Calling code—fails immediately if stub is invoked
def generate_chunk(ctx: ChunkContext) -> Chunk:
    biome = classify_terrain_type(ctx.density)  # Raises NotImplementedError
    # ... rest of logic
```

**When to Apply:**
* During feature development, if domain rules are not yet clarified with subject matter experts.
* During code review, when you spot plausible-looking logic without documented justification.
* When AI suggests thresholds, percentages, formats, classifications, or validation rules that aren't explicitly mentioned in requirements, tests, or existing code.

**Impact:** Enforced across all business, validation, and classification functions. Code review should flag speculative implementations and request stubs with referenced issues instead.

---

### AI-Assisted Development: Never Invent Domain Rules

Generated code—especially from AI assistants—must not introduce domain-specific decisions without explicit guidance. The following must never be invented:
* **Thresholds:** Cost breakpoints, size limits, age cutoffs, percentile boundaries
* **Percentages:** Discounts, tax rates, allocation factors, confidence intervals
* **Scoring Rules:** Criticality scores, risk rankings, priority calculations
* **Validation Criteria:** Format patterns, length constraints, allowed values
* **Workflow Transitions:** State machine rules, approval requirements, escalation logic
* **Business Classifications:** Categories, taxonomies, groupings

**How to Spot Invented Rules:**
If a rule or constant cannot be directly traced to:
* Existing code in the repository
* Test cases or test data
* Requirements documentation or issue descriptions
* The current task or conversation context

Then it is invented and must be replaced with a stub.

**Example: Catching Invented Logic in Code Review**

```java
// This appears in a PR but was never mentioned in requirements:
boolean isValidChunk(Chunk chunk) {
    return chunk.getBiomeId() >= 0 && chunk.getBiomeId() < 256;  // Where did 256 come from?
}

// Review response:
// "isValidChunk defines a biome ID limit of 256 that isn't mentioned anywhere.
//  Replace with UnsupportedOperationException and reference the biome registry spec."
```

```python
# This appears in a PR but was never mentioned in requirements:
def is_valid_chunk(chunk: Chunk) -> bool:
    return 0 <= chunk.biome_id < 256  # Where did 256 come from?

# Review response:
# "is_valid_chunk defines a biome ID limit of 256 that isn't mentioned anywhere.
#  Replace with NotImplementedError and reference the biome registry spec."
```

This applies universally to AI-assisted development across all modules and languages.

---

## Architecture

### Migration History Integrity (Database Migrations)

**Rule:** Migration history is append-only. Once a migration file exists (Flyway, Liquibase, Alembic, etc.), do not modify version identifiers, reparent migrations, reorder migrations, delete migration files, or create merge migrations without explicit, recorded approval from the team.

**Required workflow:**
1. Run migration validation tool and verify the migration graph.
2. Confirm exactly one head exists before creating a new migration.
3. Create a new migration using the current head.
4. Re-run validation to ensure topology is unchanged (one head).

**If multiple heads exist:**
* Stop and do not edit historical migration files.
* Do not attempt to repair topology by changing version identifiers.
* Report the detected heads, provide diagnostics, and await developer direction.

**Reasoning:**
Altering migration identifiers rewrites history and can corrupt deployed databases. Agents must preserve migration topology and surface conflicts rather than guessing a repair strategy.

**Bad:** programmatically changing version identifiers or reparenting migrations to "fix" multiple heads.

**Good:** create a new migration from the current head and, if import/data-loading steps are required, keep them idempotent and documented as pre- or post-migration operations.

---

### Keep Modules Focused on a Single Responsibility

* Each module/package should have one clear purpose.
* Avoid "god classes" or "utility packages" that accumulate unrelated functionality.
* Use package-private / internal visibility to enforce boundaries.

### Prefer Composition Over Inheritance

* Use interfaces and composition for extensibility.
* Reserve inheritance for true "is-a" relationships with shared behavior.
* Favor sealed interfaces / hierarchies for closed domain models.

### Make Illegal States Unrepresentable

* Use the type system to encode invariants (non-null, valid ranges, state machines).
* Parse/validate at boundaries; pass validated types inward.
* Use `record` / `data class` / `readonly` types for immutable data.

### Explicit Boundaries

* Define clear module boundaries with explicit public APIs.
* Use architectural tests (ArchUnit, import-linter, etc.) to enforce boundaries.
* External dependencies (DB, HTTP, FS) accessed only through interfaces at the boundary.
