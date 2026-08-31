package com.rhythmatician.voxygen.backend.voxy;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for detecting whether the Voxy LOD mod is present on the classpath.
 *
 * <p>Detection is performed by attempting to load {@code VoxelizedSection}, which has
 * <em>no</em> transitive Minecraft class references and is therefore safe to load in the
 * yarn-mapped JUnit test environment where Voxy's engine classes (which reference
 * intermediary MC names like {@code class_2841}) would fail.
 *
 * <p>Engine classes with MC references are deferred to
 * {@link VoxyEngine#ensureEngineBindings()}.  World-section field bindings are deferred
 * further to {@link VoxyWorldBinding#ensureWorldSectionBindings()}.
 */
public final class VoxyDetection {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyDetection.class);

    // ------------------------------------------------------------------ //
    //  Voxy fully-qualified class name constants
    // ------------------------------------------------------------------ //

    public static final String VOXELIZED_SECTION_CLASS = "me.cortex.voxy.common.voxelization.VoxelizedSection";
    public static final String WORLD_ENGINE_CLASS       = "me.cortex.voxy.common.world.WorldEngine";
    public static final String WORLD_UPDATER_CLASS      = "me.cortex.voxy.common.world.WorldUpdater";
    public static final String MAPPER_CLASS             = "me.cortex.voxy.common.world.other.Mapper";
    public static final String WORLD_SECTION_CLASS      = "me.cortex.voxy.common.world.WorldSection";
    public static final String WORLD_ID_CLASS           = "me.cortex.voxy.commonImpl.WorldIdentifier";
    public static final String CONV_FACTORY_CLASS       = "me.cortex.voxy.common.voxelization.WorldConversionFactory";
    public static final String VOXY_INSTANCE_CLASS      = "me.cortex.voxy.commonImpl.VoxyInstance";
    public static final String SAVING_SERVICE_CLASS     = "me.cortex.voxy.common.world.service.SectionSavingService";

    // ------------------------------------------------------------------ //
    //  Shared state (package-private — used by VoxyEngine and VoxyWorldBinding)
    // ------------------------------------------------------------------ //

    /** Cached availability flag — computed once at first access. */
    public static volatile Boolean available;

    /**
     * {@code VoxelizedSection} class — resolved during {@link #isAvailable()} because
     * it has no MC references and is safe to load in test environments.
     * Package-private so {@link VoxyEngine} can reference it when building
     * {@code insertUpdateMethod} and {@code mipSectionMethod}.
     */
    public static Class<?> voxelizedSectionClass;

    /**
     * {@code VoxelizedSection.createEmpty()} — resolved alongside
     * {@link #voxelizedSectionClass} during detection.
     */
    public static Method createEmptyMethod;

    private VoxyDetection() {}

    // ------------------------------------------------------------------ //
    //  Detection
    // ------------------------------------------------------------------ //

    /**
     * True if the core Voxy voxelization API ({@code VoxelizedSection}) is on the classpath.
     *
     * <p>{@code VoxelizedSection} fields are {@code long[]} and primitives only — no MC
     * deps — so this check succeeds even when Minecraft classes are absent.  Engine bindings
     * (WorldEngine, WorldUpdater, Mapper, etc.) are deferred to
     * {@link VoxyEngine#ensureEngineBindings()}, which is called lazily from the write path.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;

        synchronized (VoxyDetection.class) {
            if (available != null) return available;
            try {
                // VoxelizedSection has NO transitive MC class references.
                // Safe to load in plain JUnit tests.
                voxelizedSectionClass = Class.forName(VOXELIZED_SECTION_CLASS);
                createEmptyMethod = voxelizedSectionClass.getMethod("createEmpty");

                available = true;
                LOGGER.info("Voxy detected — VoxelizedSection bindings resolved");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                available = false;
                LOGGER.info("Voxy not found: " + e.getMessage());
            } catch (LinkageError e) {
                available = false;
                LOGGER.info("Voxy class loading failed: " + e.getMessage());
            }
            return available;
        }
    }

    /**
     * Throws {@link IllegalStateException} if Voxy is not available.
     * Used as a guard at the entry point of every method in
     * {@link VoxyEngine} and {@link VoxyWorldBinding}.
     */
    public static void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("Voxy is not available");
        }
    }
}
