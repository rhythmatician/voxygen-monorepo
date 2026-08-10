package com.rhythmatician.lodiffusion.voxy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages reflection-based bindings to Voxy's engine-level API and exposes the primary
 * section-lifecycle operations.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Lazily resolving all engine {@link Class} and {@link Method} handles: {@code WorldEngine},
 *       {@code WorldUpdater}, {@code Mapper}, {@code WorldConversionFactory},
 *       {@code WorldSection}, and {@code WorldIdentifier}.</li>
 *   <li>Providing the public engine operations: {@link #createEmptySection()},
 *       {@link #getMapper(Object)}, {@link #mipSection(Object, Object)},
 *       {@link #insertUpdate(Object, Object)}, {@link #sectionExists(Object, int, int, int)},
 *       {@link #getWorldEngine(net.minecraft.world.World)},
 *       {@link #getOrCreateWorldEngine(net.minecraft.world.World)}.</li>
 * </ul>
 *
 * <p>{@link VoxyDetection} handles the classpath availability check.
 * {@link VoxyWorldBinding} handles direct {@code WorldSection} field writes and voxel encoding.
 */
public final class VoxyEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyEngine.class);

    // ------------------------------------------------------------------ //
    //  Binding-ready flags (package-private — checked by VoxyWorldBinding)
    // ------------------------------------------------------------------ //

    static volatile boolean engineBindingsReady;
    static volatile boolean worldBindingsReady;
    private static volatile boolean saveQueueBindingsReady;

    // ------------------------------------------------------------------ //
    //  Reflected classes (package-private — shared with VoxyWorldBinding)
    // ------------------------------------------------------------------ //

    static Class<?> worldEngineClass;
    static Class<?> worldUpdaterClass;
    static Class<?> mapperClass;
    /** {@code WorldSection} class — also needed by {@link VoxyWorldBinding#ensureWorldSectionBindings()}. */
    static Class<?> worldSectionClass;

    // ------------------------------------------------------------------ //
    //  Reflected methods (package-private — used by VoxyWorldBinding for direct writes)
    // ------------------------------------------------------------------ //

    static Method insertUpdateMethod;       // WorldUpdater.insertUpdate(WorldEngine, VoxelizedSection)
    static Method getMapperMethod;          // WorldEngine.getMapper()
    static Method mipSectionMethod;         // WorldConversionFactory.mipSection(VoxelizedSection, Mapper)
    static Method ofEngineMethod;           // WorldIdentifier.ofEngine(World)
    static Method ofEngineNullableMethod;   // WorldIdentifier.ofEngineNullable(World)
    static Method acquireIfExistsMethod;    // WorldEngine.acquireIfExists(int, int, int, int)
    static Method acquireMethod;            // WorldEngine.acquire(int, int, int, int)
    static Method worldSectionReleaseMethod; // WorldSection.release()
    static Method markDirtyMethod;          // WorldEngine.markDirty(WorldSection)

    // Save-queue monitoring (for backpressure)
    private static Field  instanceInField;       // WorldEngine.instanceIn → VoxyInstance
    private static Field  savingServiceField;    // VoxyInstance.savingService → SectionSavingService
    private static Method getTaskCountMethod;    // SectionSavingService.getTaskCount() → int

    private VoxyEngine() {}

    // ------------------------------------------------------------------ //
    //  Lazy initialization
    // ------------------------------------------------------------------ //

    /**
     * Lazily bind all engine classes: {@code WorldEngine}, {@code WorldUpdater},
     * {@code Mapper}, {@code WorldConversionFactory}, and {@code WorldSection}.
     *
     * <p>These classes have transitive Minecraft class references ({@code class_2841}, etc.)
     * and cannot be loaded in the yarn-mapped JUnit test environment.  They are therefore
     * deferred from {@link VoxyDetection#isAvailable()}.
     *
     * <p>Called from every public method in this class and from
     * {@link VoxyWorldBinding#ensureWorldSectionBindings()}.
     *
     * @throws IllegalStateException if any engine class or method cannot be resolved
     */
    static void ensureEngineBindings() {
        VoxyDetection.ensureAvailable();
        if (engineBindingsReady) return;
        synchronized (VoxyEngine.class) {
            if (engineBindingsReady) return;
            try {
                worldEngineClass  = Class.forName(VoxyDetection.WORLD_ENGINE_CLASS);
                worldUpdaterClass = Class.forName(VoxyDetection.WORLD_UPDATER_CLASS);
                mapperClass       = Class.forName(VoxyDetection.MAPPER_CLASS);

                insertUpdateMethod = worldUpdaterClass.getMethod("insertUpdate",
                        worldEngineClass, VoxyDetection.voxelizedSectionClass);
                getMapperMethod    = worldEngineClass.getMethod("getMapper");

                Class<?> convFactoryClass = Class.forName(VoxyDetection.CONV_FACTORY_CLASS);
                mipSectionMethod = convFactoryClass.getMethod("mipSection",
                        VoxyDetection.voxelizedSectionClass, mapperClass);

                acquireIfExistsMethod = worldEngineClass.getMethod("acquireIfExists",
                        int.class, int.class, int.class, int.class);
                acquireMethod = worldEngineClass.getMethod("acquire",
                        int.class, int.class, int.class, int.class);

                worldSectionClass = Class.forName(VoxyDetection.WORLD_SECTION_CLASS);
                worldSectionReleaseMethod = worldSectionClass.getMethod("release");
                markDirtyMethod = worldEngineClass.getMethod("markDirty", worldSectionClass);

                engineBindingsReady = true;
                LOGGER.info("Voxy engine bindings resolved");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new IllegalStateException(
                        "Voxy engine classes not available: " + e.getMessage(), e);
            } catch (LinkageError e) {
                throw new IllegalStateException(
                        "Voxy engine class loading failed (MC remapping needed?): "
                        + e.getMessage(), e);
            }
        }
    }

    /**
     * Lazily bind the {@code WorldIdentifier} methods that require
     * {@code net.minecraft.world.World} as a parameter.
     *
     * <p>Deferred separately from {@link #ensureEngineBindings()} so that the core section
     * API (create/fill/mip/insert) remains usable in test environments where Minecraft
     * world classes are absent.
     *
     * @throws IllegalStateException if {@code WorldIdentifier} cannot be resolved
     */
    private static void ensureWorldBindings() {
        VoxyDetection.ensureAvailable();
        if (worldBindingsReady) return;
        synchronized (VoxyEngine.class) {
            if (worldBindingsReady) return;
            try {
                Class<?> worldIdClass = Class.forName(VoxyDetection.WORLD_ID_CLASS);
                ofEngineMethod = worldIdClass.getMethod("ofEngine",
                        net.minecraft.world.World.class);
                ofEngineNullableMethod = worldIdClass.getMethod("ofEngineNullable",
                        net.minecraft.world.World.class);
                worldBindingsReady = true;
                LOGGER.info("Voxy WorldIdentifier bindings resolved");
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
                throw new IllegalStateException(
                        "Failed to bind Voxy WorldIdentifier (Minecraft not available?): "
                        + e.getMessage(), e);
            }
        }
    }

    /**
     * Lazily bind reflection handles for Voxy's save-queue monitoring.
     *
     * <p>Path: {@code WorldEngine.instanceIn} (public) →
     * {@code VoxyInstance.savingService} (protected) →
     * {@code SectionSavingService.getTaskCount()} (public).
     *
     * <p>These are deferred from {@link #ensureEngineBindings()} because they
     * are not required for basic section writes and may fail on older Voxy
     * builds that lack these fields.
     */
    private static void ensureSaveQueueBindings() {
        ensureEngineBindings();
        if (saveQueueBindingsReady) return;
        synchronized (VoxyEngine.class) {
            if (saveQueueBindingsReady) return;
            try {
                // WorldEngine.instanceIn — public final VoxyInstance
                instanceInField = worldEngineClass.getField("instanceIn");

                // VoxyInstance.savingService — protected final SectionSavingService
                Class<?> voxyInstanceClass = Class.forName(VoxyDetection.VOXY_INSTANCE_CLASS);
                savingServiceField = voxyInstanceClass.getDeclaredField("savingService");
                savingServiceField.setAccessible(true);

                // SectionSavingService.getTaskCount() — public int
                Class<?> savingServiceClass = Class.forName(VoxyDetection.SAVING_SERVICE_CLASS);
                getTaskCountMethod = savingServiceClass.getMethod("getTaskCount");

                saveQueueBindingsReady = true;
                LOGGER.info("Voxy save-queue bindings resolved");
            } catch (ClassNotFoundException | NoSuchFieldException
                     | NoSuchMethodException | LinkageError e) {
                // Non-fatal — backpressure will simply be unavailable
                saveQueueBindingsReady = false;
                LOGGER.warn("Voxy save-queue bindings not available (backpressure disabled): "
                        + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Public engine operations
    // ------------------------------------------------------------------ //

    /**
     * Create an empty {@code VoxelizedSection} (16³ base + mip pyramid, 4681 packed voxels).
     *
     * <p>Uses {@code VoxelizedSection.createEmpty()} resolved during the lightweight
     * {@link VoxyDetection#isAvailable()} check — safe to call without a live Minecraft
     * environment.
     */
    public static Object createEmptySection() {
        VoxyDetection.ensureAvailable();
        try {
            return VoxyDetection.createEmptyMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create VoxelizedSection", e);
        }
    }

    /**
     * Get the {@code Mapper} from a {@code WorldEngine} instance.
     * The Mapper translates Minecraft {@code BlockState}/{@code Biome} into Voxy's
     * compact 64-bit voxel encoding.
     */
    public static Object getMapper(Object worldEngine) {
        ensureEngineBindings();
        try {
            return getMapperMethod.invoke(worldEngine);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Mapper from WorldEngine", e);
        }
    }

    /** Compute the mip pyramid for a {@code VoxelizedSection} in-place. */
    public static void mipSection(Object section, Object mapper) {
        ensureEngineBindings();
        try {
            mipSectionMethod.invoke(null, section, mapper);
        } catch (Exception e) {
            throw new RuntimeException("Failed to mip section", e);
        }
    }

    /**
     * Push a {@code VoxelizedSection} into a {@code WorldEngine} (blocking).
     * This is the normal L0 ingestion path via {@code WorldUpdater.insertUpdate()}.
     */
    public static void insertUpdate(Object worldEngine, Object section) {
        ensureEngineBindings();
        try {
            insertUpdateMethod.invoke(null, worldEngine, section);
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert section into Voxy world", e);
        }
    }

    /**
     * Check whether Voxy already has data for a section at the given level-0 coordinates.
     * Used to avoid overwriting real terrain with generated LODs.
     *
     * @param worldEngine the Voxy WorldEngine
     * @param sectionX    section X (blockX / 16)
     * @param sectionY    section Y (blockY / 16)
     * @param sectionZ    section Z (blockZ / 16)
     * @return {@code true} if Voxy already holds data for this section
     */
    public static boolean sectionExists(Object worldEngine,
                                         int sectionX, int sectionY, int sectionZ) {
        ensureEngineBindings();
        try {
            // acquireIfExists(lvl=0, x, y, z) returns null if no data present
            Object section = acquireIfExistsMethod.invoke(
                    worldEngine, 0, sectionX, sectionY, sectionZ);
            if (section != null) {
                worldSectionReleaseMethod.invoke(section);
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("sectionExists check failed: " + e.getMessage());
            return false;  // fail open — allow generation
        }
    }

    /**
     * Get the Voxy {@code WorldEngine} for a given Minecraft {@code World}, or {@code null}
     * if Voxy has not yet created the engine for this world.
     *
     * <p>Uses {@code WorldIdentifier.ofEngineNullable(World)}.
     */
    public static Object getWorldEngine(net.minecraft.world.World world) {
        ensureWorldBindings();
        try {
            return ofEngineNullableMethod.invoke(null, world);
        } catch (Exception e) {
            LOGGER.warn("Failed to get WorldEngine: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the Voxy {@code WorldEngine} for a given Minecraft {@code World}, creating it if
     * it does not exist yet.
     *
     * <p>Uses {@code WorldIdentifier.ofEngine(World)}.
     *
     * @return the WorldEngine (never {@code null} when Voxy is available)
     */
    public static Object getOrCreateWorldEngine(net.minecraft.world.World world) {
        ensureWorldBindings();
        try {
            return ofEngineMethod.invoke(null, world);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get/create WorldEngine", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Save-queue monitoring (backpressure)
    // ------------------------------------------------------------------ //

    /**
     * Query the depth of Voxy's save queue for a given WorldEngine.
     *
     * <p>Traverses {@code WorldEngine.instanceIn} → {@code VoxyInstance.savingService}
     * → {@code SectionSavingService.getTaskCount()}.  Returns {@code -1} if the
     * bindings could not be resolved or the WorldEngine has no attached VoxyInstance
     * (e.g., in tests).
     *
     * @param worldEngine the Voxy WorldEngine instance
     * @return number of pending save tasks, or {@code -1} if unavailable
     */
    public static int getSaveQueueDepth(Object worldEngine) {
        try {
            ensureSaveQueueBindings();
        } catch (Exception e) {
            return -1; // bindings failed — backpressure unavailable
        }
        if (!saveQueueBindingsReady || worldEngine == null) return -1;
        try {
            Object voxyInstance = instanceInField.get(worldEngine);
            if (voxyInstance == null) return -1;
            Object savingService = savingServiceField.get(voxyInstance);
            if (savingService == null) return -1;
            return (int) getTaskCountMethod.invoke(savingService);
        } catch (Exception e) {
            LOGGER.debug("getSaveQueueDepth failed: {}", e.getMessage());
            return -1;
        }
    }
}
