package com.rhythmatician.voxygen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rhythmatician.lodiffusion.ModDetection;
import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.command.LodiffusionCommand;
import com.rhythmatician.lodiffusion.onnx.OnnxModelFiles;
import io.github.lodiffusion.worldgen.WorldGenEventHandler;
import com.rhythmatician.voxygen.backend.voxy.VoxyCompat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class HelloTerrainMod implements ModInitializer {
	public static final String MOD_ID = "lodiffusion";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[LODiffusion] Mod initialized!");

		// Initialize world generation event handlers (GPU NoiseRouter extraction)
		WorldGenEventHandler.initialize();

		// Register /lodiffusion command
		try {
			CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
				LodiffusionCommand.register(dispatcher);
			});
			LOGGER.info("[LODiffusion] Registered /lodiffusion command");
		} catch (Exception e) {
			LOGGER.error("[LODiffusion] Failed to register command: {}", e.getMessage(), e);
		}

		// Detect companion mods
		LOGGER.info("[LODiffusion] {}", ModDetection.getLODStrategyInfo());

		if (VoxyCompat.isAvailable()) {
			LOGGER.info("[LODiffusion] Voxy reflection bindings OK — LOD injection path available");
		}

		// Report which ONNX model contract is currently available.
		java.nio.file.Path modelDir = Config.modelDir();
		if (OnnxModelFiles.hasFullVoxyModelSet(modelDir)) {
			LOGGER.info("[LODiffusion] Voxy 5-model set found in {}", modelDir);
		} else if (OnnxModelFiles.hasAnyVoxyModel(modelDir)) {
			LOGGER.warn("[LODiffusion] Partial Voxy model set in {} — expected voxy_l0.onnx through voxy_l4.onnx", modelDir);
		} else {
			LOGGER.warn("[LODiffusion] No Voxy ONNX model files found in {} — place voxy_l0.onnx through voxy_l4.onnx", modelDir);
		}

		LOGGER.info("[LODiffusion] Mod initialization complete!");
	}
}
