package com.winlator.star.renderer.material;

/**
 * EXPERIMENT B (GL direct-scanout pre-rotation probe) — blit the guest game texture into a
 * panel-resolution host AHB while rotating it 90° (a UV transpose), so the destination buffer is
 * already display-oriented and can be presented with transform 0 and src==dst (no SC-level scale,
 * no per-layer rotation). Combined with locking the activity to the panel's native (portrait)
 * orientation, this neutralizes BOTH the ROT_90 and the scale at once, isolating UBWC/compression
 * as the last remaining variable for HWC overlay promotion on this Adreno DPU.
 *
 * <p>DIAGNOSTIC ONLY: the transpose ({@code vUV = position.yx}) yields a consistent 90° rotation;
 * exact rotation direction / handedness / flip is intentionally NOT corrected (cursor/HUD/input
 * orientation are accepted-wrong for the probe). We only need the buffer to be produced (not black)
 * and the {@code dumpsys SurfaceFlinger} composition reading. Texture wrap must be CLAMP_TO_EDGE
 * (set by the caller) since the rotated UVs ride the [0,1] edges.
 */
public class ScanoutBlitRot90Material extends ShaderMaterial {
    public ScanoutBlitRot90Material() {
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
                // 90° rotation via transpose of the quad's [0,1] coords.
                "vUV = vec2(position.y, position.x);\n" +
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
