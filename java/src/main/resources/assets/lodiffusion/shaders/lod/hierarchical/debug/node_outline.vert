#version 450
#extension GL_ARB_shader_draw_parameters : require

layout(binding = 0, std140) uniform SceneUniform {
    mat4 VP;
    ivec3 camSecPos;
    uint screenW;
    vec3 camSubSecPos;
    uint screenH;
};

// Voxy's hierarchical node record is one uvec4.  The debug overlay needs only
// the packed position in xy; importing node.glsl also imports its dormant
// printf helper, which is valid only in Voxy's instrumented shader builder.
layout(binding = 1, std430) restrict readonly buffer NodeData {
    uvec4 nodes[];
};

layout(binding = 2, std430) restrict buffer NodeList {
    uint count;
    uint nodeQueue[];
};

layout(location = 1) out flat vec4 colour;

const vec4 LOD_COLOURS[5] = vec4[5](
    vec4(255.0,  48.0,  48.0,  24.0) / 255.0, // L0 #FF3030 red, highly transparent
    vec4(255.0, 157.0,  46.0,  24.0) / 255.0, // L1 #FF9D2E orange, highly transparent
    vec4(242.0, 232.0,  75.0,  24.0) / 255.0, // L2 #F2E84B yellow, highly transparent
    vec4( 53.0, 214.0, 232.0,  24.0) / 255.0, // L3 #35D6E8 cyan, highly transparent
    vec4(181.0, 107.0, 255.0,  24.0) / 255.0  // L4 #B56BFF violet, highly transparent
);

void main() {
    uvec2 packedPos = nodes[nodeQueue[gl_InstanceID]].xy;
    uint lodLevel = packedPos.x >> 28;
    int sectionY = (int(packedPos.x) << 4) >> 24;
    int sectionX = (int(packedPos.y) << 4) >> 8;
    int sectionZ = int((packedPos.x & ((1u << 20) - 1u)) << 4);
    sectionZ |= int(packedPos.y >> 28);
    sectionZ = (sectionZ << 8) >> 8;
    ivec3 sectionPos = ivec3(sectionX, sectionY, sectionZ);

    vec4 base = VP * vec4(vec3(((sectionPos << lodLevel) - camSecPos) << 5) - camSubSecPos, 1);
    vec4 pos = base + (VP * vec4(
        ivec3(gl_VertexID & 1, (gl_VertexID >> 2) & 1, (gl_VertexID >> 1) & 1)
            << (5 + lodLevel),
        1));
    gl_Position = pos;
    colour = LOD_COLOURS[min(lodLevel, 4u)];
}
