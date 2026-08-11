# VoxyShadowBridge Implementation Plan

## Phase 1: Request Encoding Decoding ✅ COMPLETE

### Deliverables

1. **REQUEST_ENCODING_ANALYSIS.md**
   - Complete bit-layout documentation for Voxy's 8-byte `uvec2` node requests
   - Explains how LOD level, world coordinates (x, y, z) are packed into 64 bits
   - Includes encoding rationale and validation ranges

2. **VoxyRequestDecoder.java**
   - Production-ready Java decoder for request queue entries
   - Signature: `decode(byte[] buffer, int offsetBytes) → VoxyNodeRequest`
   - Handles sign-extension correctly for signed coordinates
   - Two entry points:
     - **ByteBuffer API**: `decodeFromByteBuffer(byte[], int)` — safe for testing
     - **Native API**: `decode(long bufferAddr, int offsetBytes)` — for JNI/Unsafe access in real mixin

3. **VoxyRequestDecoderTest.java**
   - 6 comprehensive unit tests validating all bit-unpacking paths:
     - Minimal case (all zeros)
     - LOD variations (0, 1, 4)
     - Negative Y (sign-extension)
     - Complex coordinates
     - Multiple requests in buffer
   - All tests passing ✅

4. **Build Status**
   - 46 source files (added 2: Decoder + Test)
   - 0 compilation errors
   - 451 unit tests passing
   - Static analysis: all warnings in non-critical areas ($TOOL configuration)

---

## Phase 2: VoxyShadowBridge Mixin Implementation (NEXT)

### Architecture Overview

```
HierarchicalOcclusionTraverser.forwardDownloadResult(long ptr, long size)
    ↓
[INJECT MIXIN @ModifyVariable / @Inject]
    ↓
VoxyShadowBridge.interceptRequestBatch(long ptr, long size)
    ├─ Read request count
    ├─ For each uvec2:
    │   ├─ Decode via VoxyRequestDecoder
    │   ├─ Filter: LOD ∈ [1,4] && within generation horizon
    │   └─ Enqueue to ShadowRouterJobQueue
    ├─ Continue original processing (Voxy's nodeManager.submitRequestBatch())
    └─ Return
```

### Implementation Steps

#### Step 1: VoxyShadowBridge Mixin Class
- **Location**: `src/main/java/net/lodiffusion/mixin/voxy/VoxyShadowBridgeMixin.java`
- **Target**: `me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser`
- **Method**: `forwardDownloadResult(long ptr, long size)` (lines 336–348 in Voxy source)
- **Approach**: 
  - Use Fabric `@Mixin` annotation
  - Use `@Inject` with `callback=` to run **before** `nodeManager.submitRequestBatch()`
  - Use `CallbackInfo` to allow original method to continue (don't cancel)

**Pseudocode:**
```java
@Mixin(HierarchicalOcclusionTraverser.class)
public class VoxyShadowBridgeMixin {
    @Inject(
        method = "forwardDownloadResult",
        at = @At(value = "INVOKE", 
                 target = "me/cortex/voxy/client/core/node/HierarchicalNodeManager.submitRequestBatch(...)"),
        locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void interceptRequests(long ptr, long size, CallbackInfo ci,
                                   int count, MemoryBuffer buffer) {
        if (count > 0 && ShadowRuntime.isEnabled()) {
            VoxyNodeRequest[] requests = new VoxyNodeRequest[count];
            long requestPtr = ptr + 8;
            
            for (int i = 0; i < count; i++) {
                requests[i] = VoxyRequestDecoder.decode(requestPtr, i * 8);
                if (shouldEnqueue(requests[i])) {
                    ShadowRouterJobQueue.enqueue(requests[i]);
                }
            }
        }
    }
}
```

#### Step 2: ShadowRouterJobQueue Class
- **Location**: `src/main/java/net/lodiffusion/shadow/ShadowRouterJobQueue.java`
- **Responsibilities**:
  - Thread-safe queue accepting `VoxyNodeRequest` entries
  - Priority ordering by distance to player (heapq-style) or FIFO
  - Dequeue interface for TerrainComputeDispatcher to pull work
  - Per-LOD work bucket (5 separate queues for LOD 0–4)

**API:**
```java
public class ShadowRouterJobQueue {
    public static void enqueue(VoxyNodeRequest req);
    public static VoxyNodeRequest dequeue(int preferredLod);
    public static int size();
    public static void clear();
}
```

#### Step 3: ShadowRouterJobQueue ↔ TerrainComputeDispatcher Integration
- Modify `TerrainComputeDispatcher` to support work-pulling:
  - Add `acceptNextRequest()` method
  - Call `ShadowRouterJobQueue.dequeue()` to get next job
  - Parse `(lod, x, y, z)` coordinates
  - Map to chunk space and dispatch

#### Step 4: Binding 7 → RocksDB Packer
- After TerrainComputeDispatcher generates density, pack into Voxy format
- **File**: `src/main/java/net/lodiffusion/shadow/WorldSectionPacker.java`
- **Responsibility**: Convert 16×384×16 density grid (Binding 7) to Voxy's `WorldSection` (64-bit keys + block arrays)
- Guard: insert-only check before RocksDB write

#### Step 5: Mixin Registration
- Add to `fabric.mod.json`:
  ```json
  "mixins": [
    "lodiffusion.mixins.json"
  ]
  ```
- Create `src/main/resources/lodiffusion.mixins.json`:
  ```json
  {
    "required": true,
    "package": "net.lodiffusion.mixin",
    "compatibilityLevel": "JAVA_21",
    "client": [
      "voxy.VoxyShadowBridgeMixin"
    ]
  }
  ```

---

## Sequence of Work

| Phase | File(s) | Est. Effort | Blocker? |
|-------|---------|-------------|----------|
| 1 | REQUEST_ENCODING_ANALYSIS.md | ✅ 2h | No |
| 1 | VoxyRequestDecoder.java | ✅ 3h | No |
| 1 | VoxyRequestDecoderTest.java | ✅ 2h | No |
| 2 | VoxyShadowBridgeMixin.java | → 4h | Yes (blocks Binding 7 work) |
| 2 | ShadowRouterJobQueue.java | → 3h | Yes (feeds dispatcher) |
| 3 | TerrainComputeDispatcher.acceptNextRequest() | → 2h | Yes (consumes queue) |
| 3 | WorldSectionPacker.java | → 4h | Yes (writes RocksDB) |
| 4 | Mixin registration in fabric.mod.json | → 1h | Final step |
| 5 | Integration testing in Minecraft + Voxy | → 8h | Final validation |

**Critical Path**: Mixin → Queue → Dispatcher → Packer → Registration → Test

---

## Validation Checkpoints

### Checkpoint 1: Mixin Injection
- [ ] Voxy loads without error with VoxyShadowBridge mixin installed
- [ ] Forge/Fabric logs show successful mixin application
- [ ] No crashes when HierarchicalOcclusionTraverser traverses (test by flying around)

### Checkpoint 2: Request Decoding
- [ ] VoxyShadowBridgeMixin successfully decodes 100+ request batches (add debug logging)
- [ ] Decoded LOD and coordinates are reasonable (LOD ∈ [0,4], coords within ±1000 blocks)
- [ ] No crashes due to memory access errors

### Checkpoint 3: Queue Acceptance
- [ ] ShadowRouterJobQueue size grows as Voxy generates requests
- [ ] Dequeue operations return requests in priority order
- [ ] Queue drains as TerrainComputeDispatcher pulls work

### Checkpoint 4: Density Generation
- [ ] TerrainComputeDispatcher successfully dispatches for dequeued requests
- [ ] GPU density generation completes without errors (GL error log)
- [ ] Binding 7 readback returns non-zero density values

### Checkpoint 5: RocksDB Write
- [ ] Density grid packs correctly into WorldSection format
- [ ] RocksDB insert-only guard prevents overwrites
- [ ] Voxy can read back generated sections

### Checkpoint 6: Full Integration
- [ ] Launch Minecraft with LODiffusion + Voxy
- [ ] Fly to distance chunks (beyond LOD0 render distance)
- [ ] Observe terrain rendering at LOD1, LOD2, etc.
- [ ] No visible seams or artifacts
- [ ] Performance: <100ms per generated section

---

## Known Limitations & Future Work

### Current Scope (Phase 1)
- LOD1–4 terrain generation via demand-driven interception
- No cave/structure generation
- No player movement prediction (reactive only)

### TODO (Phase 2+)
- Smooth LOD→vanilla transition blending
- Underground y-slab skipping (heuristic visibility check)
- Residual prediction mode (parent + delta refinement)
- Asynchronous dispatch (background thread for queue processing)

---

## References

- **Voxy Source**: `reference-code/voxy/src/main/java/me/cortex.voxy.client.core.rendering.hierachical/HierarchicalOcclusionTraverser.java`
- **Request Format**: `REQUEST_ENCODING_ANALYSIS.md` (this folder)
- **Decoder Test**: `src/test/java/net/lodiffusion/shadow/VoxyRequestDecoderTest.java`
- **Fabric Mixin Guide**: https://github.com/FabricMC/fabric/wiki/Using-Mixins
