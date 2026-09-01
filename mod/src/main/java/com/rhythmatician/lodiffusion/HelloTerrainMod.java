package com.rhythmatician.lodiffusion;

import net.fabricmc.api.ModInitializer;

/**
 * Legacy shim: Fabric still requires mod id {@code lodiffusion} but entrypoint now lives in
 * {@link com.rhythmatician.voxygen.HelloTerrainMod}. This shim preserves the old
 * {@code com.rhythmatician.lodiffusion.HelloTerrainMod} FQN for any reflective callers
 * while delegating to the canonical voxygen implementation.
 */
public class HelloTerrainMod implements ModInitializer {
	public static final String MOD_ID = com.rhythmatician.voxygen.HelloTerrainMod.MOD_ID;
	public static final org.slf4j.Logger LOGGER = com.rhythmatician.voxygen.HelloTerrainMod.LOGGER;

	@Override
	public void onInitialize() {
		new com.rhythmatician.voxygen.HelloTerrainMod().onInitialize();
	}
}
