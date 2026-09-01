// =============================================================================
// mc_improved_noise.glsl
// Port of net.minecraft.world.level.levelgen.synth.ImprovedNoise
//
// This file is NOT a standalone shader. It is concatenated into terrain_compute.comp
// by the Java loader (GlslShaderLoader). It depends on the following SSBOs being
// declared in the including shader:
//
//   layout(binding = 0, std430) buffer ImprovedOrigins { float data[]; };
//     - 4 floats per instance: [xo, yo, zo, _pad]
//     - Access: improved_origins.data[instanceIdx * 4 + 0..3]
//
//   layout(binding = 1, std430) buffer ImprovedPerms { uint data[]; };
//     - 256 uints per instance, flat
//     - Access: improved_perms.data[instanceIdx * 256 + (x & 255)]
// =============================================================================

// Gradient table from SimplexNoise.GRADIENT (shared between Improved and Simplex noise).
// Exactly 16 entries, indexed with hash & 0xF.
const ivec3 MC_GRADIENT[16] = ivec3[16](
    ivec3( 1, 1, 0), ivec3(-1, 1, 0), ivec3( 1,-1, 0), ivec3(-1,-1, 0),
    ivec3( 1, 0, 1), ivec3(-1, 0, 1), ivec3( 1, 0,-1), ivec3(-1, 0,-1),
    ivec3( 0, 1, 1), ivec3( 0,-1, 1), ivec3( 0, 1,-1), ivec3( 0,-1,-1),
    ivec3( 1, 1, 0), ivec3( 0,-1, 1), ivec3(-1, 1, 0), ivec3( 0,-1,-1)
);

// Mth.smoothstep: QUINTIC fade curve = 6t^5 - 15t^4 + 10t^3
// NOTE: This is NOT the same as GLSL's built-in smoothstep() which is cubic.
float mc_smoothstep(float t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

// Mth.lerp3: trilinear interpolation.
// Arguments named to match Minecraft's parameter order:
//   t1=x, t2=y, t3=z, then values ordered as binary (z,y,x low-to-high bits)
float mc_lerp3(float t1, float t2, float t3,
               float a000, float a100, float a010, float a110,
               float a001, float a101, float a011, float a111) {
    // Interpolate x first (t1), then y (t2), then z (t3)
    float y0z0 = mix(a000, a100, t1);
    float y1z0 = mix(a010, a110, t1);
    float y0z1 = mix(a001, a101, t1);
    float y1z1 = mix(a011, a111, t1);
    float z0   = mix(y0z0, y1z0, t2);
    float z1   = mix(y0z1, y1z1, t2);
    return mix(z0, z1, t3);
}

// Permutation table lookup for a given ImprovedNoise instance.
// Wraps at 256, matching Java's `this.p[x & 0xFF] & 0xFF`.
int mc_p(int instanceIdx, int x) {
    return int(improved_perms.data[instanceIdx * 256 + (x & 255)]);
}

// Dot product with gradient vector.
// Matches Java's ImprovedNoise.gradDot(hash, x, y, z)
// = SimplexNoise.dot(GRADIENT[hash & 0xF], x, y, z)
float mc_grad_dot(int hash, float x, float y, float z) {
    ivec3 g = MC_GRADIENT[hash & 0xF];
    return float(g.x) * x + float(g.y) * y + float(g.z) * z;
}

// Core perlin noise sample for one ImprovedNoise instance.
// Matches Java's ImprovedNoise.noise(x, y, z) — the zero-yScale variant.
// (The deprecated yScale/yFudge path is only used in legacy blended noise and
//  is not needed for modern worldgen density functions.)
float mc_improved_noise(int instanceIdx, float _x, float _y, float _z) {
    // Add per-instance origin offsets (seeded from world seed)
    float x = _x + improved_origins.data[instanceIdx * 4 + 0];
    float y = _y + improved_origins.data[instanceIdx * 4 + 1];
    float z = _z + improved_origins.data[instanceIdx * 4 + 2];

    // Integer cell coordinates
    int xf = int(floor(x));
    int yf = int(floor(y));
    int zf = int(floor(z));

    // Fractional offsets within cell
    float xr = x - float(xf);
    float yr = y - float(yf);
    float zr = z - float(zf);

    // Permutation table traversal — exactly as in Java's sampleAndLerp()
    int x0   = mc_p(instanceIdx, xf);
    int x1   = mc_p(instanceIdx, xf + 1);
    int xy00 = mc_p(instanceIdx, x0 + yf);
    int xy01 = mc_p(instanceIdx, x0 + yf + 1);
    int xy10 = mc_p(instanceIdx, x1 + yf);
    int xy11 = mc_p(instanceIdx, x1 + yf + 1);

    // 8 gradient dot products for the 8 corners of the unit cube
    float d000 = mc_grad_dot(mc_p(instanceIdx, xy00 + zf),     xr,        yr,        zr);
    float d100 = mc_grad_dot(mc_p(instanceIdx, xy10 + zf),     xr - 1.0,  yr,        zr);
    float d010 = mc_grad_dot(mc_p(instanceIdx, xy01 + zf),     xr,        yr - 1.0,  zr);
    float d110 = mc_grad_dot(mc_p(instanceIdx, xy11 + zf),     xr - 1.0,  yr - 1.0,  zr);
    float d001 = mc_grad_dot(mc_p(instanceIdx, xy00 + zf + 1), xr,        yr,        zr - 1.0);
    float d101 = mc_grad_dot(mc_p(instanceIdx, xy10 + zf + 1), xr - 1.0,  yr,        zr - 1.0);
    float d011 = mc_grad_dot(mc_p(instanceIdx, xy01 + zf + 1), xr,        yr - 1.0,  zr - 1.0);
    float d111 = mc_grad_dot(mc_p(instanceIdx, xy11 + zf + 1), xr - 1.0,  yr - 1.0,  zr - 1.0);

    // Smoothstep alphas — yAlpha uses yrOriginal which equals yr (yFudge=0 path)
    float xa = mc_smoothstep(xr);
    float ya = mc_smoothstep(yr);
    float za = mc_smoothstep(zr);

    return mc_lerp3(xa, ya, za,
                    d000, d100, d010, d110,
                    d001, d101, d011, d111);
}
