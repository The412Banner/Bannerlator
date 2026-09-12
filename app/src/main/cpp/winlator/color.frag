#version 450

// =============================================================================
//  Color grade (brightness / contrast / saturation / gamma) post effect.
//
//  Ported verbatim from the OpenGL ColorEffect.java shader. The four terms are
//  prepared CPU-side (planUpscaleFrame) exactly as the GL path does:
//      brightness = brightnessSlider / 100   clamped to [-1, 1]
//      contrast   = contrastSlider   / 100   clamped to [ 0, 2]
//      saturation = saturationSlider / 100   clamped to [ 0, 2]
//      gamma      = gammaSlider               clamped to [0.1, 5]
//  (the clamps replicate ColorEffectMaterial.use()). Neutral = (0,0,1,1) -> no-op,
//  matching the GL "remove the effect" check; the chain skips Color when neutral.
//
//  Term ORDER inside this shader is brightness -> contrast -> saturation -> gamma.
//  Saturation sits after contrast and before gamma deliberately: it mixes towards
//  Rec.709 luma of the already-graded linear-ish signal, so the greyscale it blends
//  against is the one the viewer sees, and the gamma curve is still applied last to
//  the finished colour. ColorEffect.java applies its terms in the same order, so a
//  Screen Effect "Look" grades identically on the OpenGL and Vulkan renderers.
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
//  `saturation` is APPENDED at the end of the block so the offsets of the three
//  original terms are unchanged.
//  Runs after Toon, before CAS in the canonical chain (grade the clean image).
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4  ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    float brightness; // additive, [-1, 1]
    float contrast;   // [0, 2]; effective multiplier = clamp(contrast+1, 0.5, 2)
    float gamma;      // [0.1, 5]
    float saturation; // [0, 2]; 1 = unchanged, 0 = greyscale
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texelColor = texture(InputTexture, fragTexCoord);
    vec3 color = texelColor.rgb;
    color = clamp(color + pc.brightness, 0.0, 1.0);
    color = (color - 0.5) * clamp(pc.contrast + 1.0, 0.5, 2.0) + 0.5;
    vec3 grey = vec3(dot(color, vec3(0.2126, 0.7152, 0.0722)));
    color = mix(grey, color, clamp(pc.saturation, 0.0, 2.0));
    color = pow(color, vec3(1.0 / pc.gamma));
    outColor = vec4(color, texelColor.a);
}
