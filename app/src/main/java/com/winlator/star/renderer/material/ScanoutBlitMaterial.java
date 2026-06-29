package com.winlator.star.renderer.material;

/**
 * EXPERIMENT A (GL direct-scanout COMPOSER_OVERLAY probe) — a straight passthrough blit used to copy
 * the guest game texture into a host-allocated, overlay-eligible AHardwareBuffer before handing that
 * buffer to the scanout SurfaceControl. Identical to {@link WindowMaterial} except the sampled V
 * coordinate is flipped (vUV.y = 1 - position.y).
 *
 * <p>Why the flip: the guest game buffer is top-down (gralloc row 0 = image top) and is presented
 * directly today. When we render it into an AHB-backed FBO instead, GL's framebuffer origin is
 * bottom-left, so a non-flipped copy lands the image upside-down in the destination buffer's memory
 * (SurfaceFlinger then samples that buffer top-down -> inverted cube). Pre-flipping V here makes the
 * destination buffer's memory layout match the guest's, so the on-screen result is unchanged.
 * (The existing screenshot path in GLRenderer needs the same vertical flip after an FBO render.)
 */
public class ScanoutBlitMaterial extends ShaderMaterial {
    public ScanoutBlitMaterial() {
        setUniformNames("xform", "viewSize", "texture");
    }

    @Override
    protected String getVertexShader() {
        return
            "uniform float xform[6];\n" +
            "uniform vec2 viewSize;\n" +
            "attribute vec2 position;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "vUV = vec2(position.x, 1.0 - position.y);\n" +
                "vec2 transformedPos = applyXForm(position, xform);\n" +
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);\n" +
            "}"
        ;
    }

    @Override
    protected String getFragmentShader() {
        return
            "precision mediump float;\n" +

            "uniform sampler2D texture;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "gl_FragColor = vec4(texture2D(texture, vUV).rgb, 1.0);\n" +
            "}"
        ;
    }
}
