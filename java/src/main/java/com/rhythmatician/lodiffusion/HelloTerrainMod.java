package com.rhythmatician.lodiffusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rhythmatician.lodiffusion.command.LodiffusionCommand;
import com.rhythmatician.lodiffusion.command.NoiseDumperCommand;
import java.nio.file.Files;
import com.rhythmatician.lodiffusion.voxy.VoxyCompat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class HelloTerrainMod implements ModInitializer {
	public static final String MOD_ID = "lodiffusion";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[LODiffusion] Mod initialized!");

		// Register /lodiffusion command
		try {
			CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
				LodiffusionCommand.register(dispatcher);
				NoiseDumperCommand.register(dispatcher);
			});
			LOGGER.info("[LODiffusion] Registered /lodiffusion and /dumpnoise commands");
		} catch (Exception e) {
			LOGGER.error("[LODiffusion] Failed to register command: {}", e.getMessage(), e);
		}

		// Detect companion mods
		LOGGER.info("[LODiffusion] {}", ModDetection.getLODStrategyInfo());

		if (VoxyCompat.isAvailable()) {
			LOGGER.info("[LODiffusion] Voxy reflection bindings OK — LOD injection path available");
		}

		// Check if octree model files are present in the model dir (v5.octree pipeline)
		java.nio.file.Path modelDir = Config.modelDir();
		boolean modelsPresent = Files.isRegularFile(modelDir.resolve("octree_init.onnx"))
				&& Files.isRegularFile(modelDir.resolve("octree_refine.onnx"))
				&& Files.isRegularFile(modelDir.resolve("octree_leaf.onnx"));
		if (modelsPresent) {
			LOGGER.info("[LODiffusion] Octree ONNX models found in {}", modelDir);
		} else {
			LOGGER.warn("[LODiffusion] Octree model files not found in {} — LOD generation will fail until models are placed", modelDir);
		}

		LOGGER.info("[LODiffusion] Mod initialization complete!");
	}
}
