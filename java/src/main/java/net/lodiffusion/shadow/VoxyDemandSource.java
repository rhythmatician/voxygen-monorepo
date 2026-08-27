package net.lodiffusion.shadow;

/** Origin of a scheduler request; policy can evolve without redefining physical work. */
public enum VoxyDemandSource {
    VOXY_WATCH_BRIDGE,
    HORIZON_SEED,
    VANILLA_RADIUS_ANNULUS,
    VANILLA_OCCUPANCY_BOUNDARY,
    SCREEN_SPACE_SELECTOR,
    PARENT_DEPENDENCY
}
