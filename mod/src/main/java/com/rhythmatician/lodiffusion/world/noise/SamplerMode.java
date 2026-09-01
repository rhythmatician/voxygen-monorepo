package com.rhythmatician.lodiffusion.world.noise;

/**
 * Defines the <b>end-to-end pipeline mode</b> — which sampler feeds
 * which consumer (model / direct output / comparison).
 *
 * <p>This enum resolves the ambiguity identified in the contract hardening
 * phase: without an explicit mode, you can end up validating GPU vs CPU
 * at the signal layer while still training or inferring on mixed inputs
 * at the model layer.
 *
 * <h2>Mode matrix</h2>
 * <pre>
 *   Mode              Signal Source    Model Input    Output Authority
 *   ────────────────  ──────────────   ─────────────  ────────────────
 *   CPU_ONLY          vanilla CPU      CPU signals    model
 *   GPU_ONLY          GPU shadow       GPU signals    model
 *   CPU_MODEL         vanilla CPU      CPU signals    model
 *   GPU_MODEL         GPU shadow       GPU signals    model
 *   CPU_VS_GPU        both (shadow)    CPU signals    CPU (reference)
 * </pre>
 *
 * <p>{@code CPU_ONLY} and {@code CPU_MODEL} are functionally identical
 * today (the model always receives sampler output), but the distinction
 * future-proofs for a world where the model could receive pre-computed
 * features instead of raw sampler output.
 *
 * <h2>Relationship to {@code terrainBackend}</h2>
 * <table>
 *   <tr><th>terrainBackend</th><th>SamplerMode</th></tr>
 *   <tr><td>vanilla</td><td>{@link #CPU_ONLY}</td></tr>
 *   <tr><td>gpu</td><td>{@link #GPU_ONLY}</td></tr>
 *   <tr><td>shadow</td><td>{@link #CPU_VS_GPU_COMPARE}</td></tr>
 *   <tr><td>auto</td><td>resolves to {@link #CPU_ONLY} currently</td></tr>
 * </table>
 *
 * @see NoiseRouterSamplerFactory
 * @see ShadowValidatingSampler
 */
public enum SamplerMode {

    /**
     * Vanilla CPU sampling → model inference.
     * The baseline production path.  Bit-exact with vanilla Minecraft
     * at the signal layer; model provides block prediction.
     */
    CPU_ONLY("cpu_only"),

    /**
     * GPU shadow router → model inference.
     * Full GPU pipeline.  Should only be promoted to production when
     * the parity contract thresholds are met.
     */
    GPU_ONLY("gpu_only"),

    /**
     * Vanilla CPU sampling → model inference.
     * Explicit annotation that the model is consuming CPU-sourced signals.
     * Functionally identical to {@link #CPU_ONLY} but semantically distinct
     * for pipeline auditing.
     */
    CPU_MODEL("cpu_model"),

    /**
     * GPU shadow router → model inference.
     * Explicit annotation for the GPU-sourced model path.
     * Functionally identical to {@link #GPU_ONLY}.
     */
    GPU_MODEL("gpu_model"),

    /**
     * Both CPU and GPU sampled in parallel; CPU result is authoritative.
     * Divergence is measured and reported.  The model receives <b>CPU</b>
     * signals — GPU signals are used only for comparison.
     *
     * <p>This mode answers the critical question: "which sampler feeds the
     * model during validation?"  Answer: always the CPU reference.
     */
    CPU_VS_GPU_COMPARE("cpu_vs_gpu_compare");

    private final String configKey;

    SamplerMode(String configKey) {
        this.configKey = configKey;
    }

    /** The string used in config files and logs. */
    public String configKey() {
        return configKey;
    }

    /**
     * Resolve a {@code terrainBackend} config string to a {@link SamplerMode}.
     *
     * @param backendKey the resolved backend key (vanilla/gpu/shadow)
     * @return corresponding mode
     */
    public static SamplerMode fromBackendKey(String backendKey) {
        return switch (backendKey) {
            case "vanilla" -> CPU_ONLY;
            case "gpu"     -> GPU_ONLY;
            case "shadow"  -> CPU_VS_GPU_COMPARE;
            default        -> CPU_ONLY;
        };
    }

    /**
     * Whether this mode feeds CPU-sourced signals to the model.
     * Critical for determining training/inference data provenance.
     */
    public boolean modelReceivesCpuSignals() {
        return switch (this) {
            case CPU_ONLY, CPU_MODEL, CPU_VS_GPU_COMPARE -> true;
            case GPU_ONLY, GPU_MODEL -> false;
        };
    }

    /**
     * Whether this mode includes GPU sampling (for warming / comparison).
     */
    public boolean involvesGpu() {
        return switch (this) {
            case GPU_ONLY, GPU_MODEL, CPU_VS_GPU_COMPARE -> true;
            case CPU_ONLY, CPU_MODEL -> false;
        };
    }
}
