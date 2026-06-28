package com.winlator.star.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import com.winlator.star.renderer.effects.Effect;
import com.winlator.star.renderer.effects.ToonEffect;
import com.winlator.star.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    // Constants
    private static final String TAG = "EffectComposer";
    private boolean isRendering = false;

    // Instance fields
    private final List<Effect> effects = new ArrayList<>();
    private RenderTarget readBuffer;
    private RenderTarget writeBuffer;
    private final GLRenderer renderer;

    // ---- GL spatial-upscaler (SGSR / FSR) state ----------------------------------------
    // Mirrors the Vulkan "Scaling mode" picker: 0=None 1=Linear 2=Nearest 3=SGSR 4=FSR
    // 5=FSR(Fit) 6=Sharpen(CAS). Only the spatial modes (3/4/5) use the low-res render
    // target below; None/Linear/Nearest drive GLRenderer.setFilterMode and Sharpen reuses
    // the existing CAS post-effect, so for those the upscaler slots stay null and render()
    // falls through to the unchanged default path. Drawer-only / session-live.
    private int   upscalerMode     = 0;
    private float upscaleSharpness = 0.75f;             // 0..1; 0.75 == legacy default
    // Internal render scale for the low-res stage. 0.667 (2/3) == FSR "Quality" (a 1.5x
    // upscale): a good general default that gives the upscaler real work while staying
    // cheap. Fixed / session-live; not user-exposed.
    private static final float RENDER_SCALE = 0.667f;
    private RenderTarget lowResBuffer;                  // allocated lazily, sub-surface size
    private int lowResW, lowResH;                       // allocated low-res dimensions
    // The dedicated upscaler pass(es). For SGSR only `upscalePrimary` is set (single pass);
    // for FSR `upscalePrimary` = EASU and `upscaleSecondary` = RCAS (two passes). Both null
    // for every non-spatial mode -> isSpatialUpscale() false -> default render path.
    private Effect upscalePrimary;
    private Effect upscaleSecondary;
    // Scaling-mode 6 (Sharpen) reuses the existing AMD CAS post-effect (FSREffect) rather
    // than the low-res stage. Tracked as a picker-owned instance so switching modes only
    // ever removes the picker's CAS, never the one the separate "Sharpen (CAS)" toggle adds.
    private Effect pickerCasEffect;

    // Constructor
    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
//        Log.d(TAG, "EffectComposer created");
    }

    // Initializes the buffers if they are not already initialized
    private void initBuffers() {
//        Log.d(TAG, "initBuffers() called");

        if (readBuffer == null) {
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.getSurfaceWidth(), renderer.getSurfaceHeight());
//            Log.d(TAG, "Initialized readBuffer with size: " + renderer.getSurfaceWidth() + "x" + renderer.getSurfaceHeight());
        }

        if (writeBuffer == null) {
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.getSurfaceWidth(), renderer.getSurfaceHeight());
//            Log.d(TAG, "Initialized writeBuffer with size: " + renderer.getSurfaceWidth() + "x" + renderer.getSurfaceHeight());
        }
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) {
            effects.add(effect);
//            Log.d(TAG, "Effect added: " + effect.getClass().getSimpleName());
        } else {
//            Log.d(TAG, "Effect already present: " + effect.getClass().getSimpleName());
        }
        // Move this call to the end of a batch effect addition or modification to prevent immediate rendering
        renderer.xServerView.requestRender();
    }



    // Gets an effect by its class type
    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
//        Log.d(TAG, "getEffect() called for: " + effectClass.getSimpleName());

        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) {
//                Log.d(TAG, "Effect found: " + effectClass.getSimpleName());
                return effectClass.cast(effect);
            }
        }
//        Log.d(TAG, "Effect not found: " + effectClass.getSimpleName());
        return null;
    }

    // Checks if there are any effects present
    public synchronized boolean hasEffects() {
        boolean hasEffects = !effects.isEmpty();
//        Log.d(TAG, "hasEffects() called. Effects present: " + hasEffects);
        return hasEffects;
    }

    // Removes a specific effect from the composer
    public synchronized void removeEffect(Effect effect) {
        if (effects.remove(effect)) {
//            Log.d(TAG, "Effect removed: " + effect.getClass().getSimpleName());
        } else {
//            Log.d(TAG, "Effect not found for removal: " + effect.getClass().getSimpleName());
        }
        renderer.xServerView.requestRender();
    }

    // Renders all the effects in the composer
    public synchronized void render() {
        // Check for recursive rendering
        if (isRendering) {
//            Log.d(TAG, "Render already in progress, skipping.");
            return;
        }

        isRendering = true; // Set flag to true

//        Log.d(TAG, "render() called");

        initBuffers();

        // Spatial-upscaler path (SGSR / FSR): render the scene into a low-res target and
        // upsample it back to surface resolution before the post-effect chain. Strictly
        // gated to the spatial modes with a wired upscaler pass and the windowed/
        // non-magnified case (canSpatialUpscale()); for every other frame this is skipped
        // and the default path below runs unchanged.
        if (isSpatialUpscale() && canSpatialUpscale()) {
            renderUpscaled();
            isRendering = false;
            return;
        }

        // Set up framebuffer if there are effects to render
        if (hasEffects()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
//            Log.d(TAG, "Binding to readBuffer framebuffer: " + readBuffer.getFramebuffer());
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
//            Log.d(TAG, "Binding to default framebuffer (0)");
        }

        // Draw the initial frame
        renderer.drawFrame();
//        Log.d(TAG, "Initial frame drawn");

        // Iterate through each effect and render it
        for (Effect effect : effects) {
            boolean renderToScreen = effect == effects.get(effects.size() - 1);
            int targetFramebuffer = renderToScreen ? 0 : writeBuffer.getFramebuffer();

            // Trivial pure-copy stage: an effect with no shader material is not a real effect, so a
            // single full-frame glBlitFramebuffer (GLES30, LINEAR) of the read buffer replaces the
            // program-bind + clear + textured-quad. This is also strictly more correct than before:
            // the old path cleared the target then renderEffect() early-returned on the null
            // material, leaving the stage black. Real shader effects (Color/FXAA/Toon/CRT/NTSC/CAS)
            // all carry a material and take the unchanged renderEffect() path below, so their
            // output is bit-for-bit identical.
            if (effect.getMaterial() == null) {
                blitReadBufferTo(targetFramebuffer);
                swapBuffers();
                continue;
            }

            // Bind appropriate framebuffer
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
//            Log.d(TAG, "Binding to " + (renderToScreen ? "screen" : "writeBuffer") + " framebuffer: " + targetFramebuffer);

            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.setViewportNeedsUpdate(true);
//            Log.d(TAG, "Viewport updated to size: " + renderer.surfaceWidth + "x" + renderer.surfaceHeight);

            // Clear the buffer
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//            Log.d(TAG, "Framebuffer cleared");

            // Render the effect
            renderEffect(effect);
//            Log.d(TAG, "Effect rendered: " + effect.getClass().getSimpleName());

            // Swap the read and write buffers
            swapBuffers();
//            Log.d(TAG, "Buffers swapped");
        }

        isRendering = false; // Reset flag after rendering
    }

    // Renders a single effect
    private void renderEffect(Effect effect) {
//        Log.d(TAG, "renderEffect() called for: " + effect.getClass().getSimpleName());

        ShaderMaterial material = effect.getMaterial();
        if (material == null) {
//            Log.e(TAG, "Material is null for effect: " + effect.getClass().getSimpleName());
            return;
        }

        material.use();
//        Log.d(TAG, "ShaderMaterial used: " + material.getClass().getSimpleName());

        // Bind the quad vertices to the shader program
        renderer.getQuadVertices().bind(material.programId);
//        Log.d(TAG, "Quad vertices bound to program ID: " + material.programId);

        // Set uniform values
        material.setUniformVec2("resolution", renderer.surfaceWidth, renderer.surfaceHeight);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt("screenTexture", 0);
//        Log.d(TAG, "Uniforms set: resolution=" + renderer.surfaceWidth + "x" + renderer.surfaceHeight + ", screenTexture=" + readBuffer.getTextureId());

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
//        Log.d(TAG, "Quad drawn");

        // Unbind the texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
//        Log.d(TAG, "Texture unbound");
    }

    // Pure copy/scale of the read buffer into the given framebuffer using glBlitFramebuffer
    // (GLES30), avoiding a shader program bind + full-screen quad for trivial copy stages. Source
    // and dest are both the surface size today; LINEAR is chosen so a future size mismatch (the
    // deferred low-res render-scale step) resolves cleanly. Used only for material-less passes, so
    // it never touches a real shader effect.
    private void blitReadBufferTo(int targetFramebuffer) {
        int w = renderer.surfaceWidth;
        int h = renderer.surfaceHeight;
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readBuffer.getFramebuffer());
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
        // glBlitFramebuffer honors the scissor box; drawFrame() may have left it enabled, so clear
        // it for the full-frame copy.
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    // Swaps the read and write buffers
    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
//        Log.d(TAG, "swapBuffers() called. Buffers swapped.");
    }

    // Add a method to add the ToonEffect
    public synchronized void toggleToonEffect() {
        ToonEffect toonEffect = getEffect(ToonEffect.class);
        if (toonEffect != null) {
            removeEffect(toonEffect); // Remove if already present
            Log.d(TAG, "ToonEffect removed");
        } else {
            addEffect(new ToonEffect()); // Add if not present
            Log.d(TAG, "ToonEffect added");
        }
        renderer.xServerView.requestRender();
    }

    // ====================================================================================
    //  GL spatial upscaler (SGSR / FSR) — low-res render-target stage
    // ====================================================================================

    // True when the composer has anything to do this frame: either post-effects or an
    // active spatial upscaler. Widens the old hasEffects()-only gate in GLRenderer so a
    // scaling mode with no post-effects still routes through render(). Identical to
    // hasEffects() whenever no spatial upscaler is engaged.
    public synchronized boolean isActive() {
        return hasEffects() || isSpatialUpscale();
    }

    // A spatial upscaler is engaged only when a spatial mode (3/4/5) selected one of the
    // dedicated upscaler passes. Modes 0/1/2/6 leave the slots null.
    private boolean isSpatialUpscale() {
        return upscalePrimary != null;
    }

    // The low-res render-target stage replicates the default (windowed, magnifier-enabled,
    // zoom == 1) frame path. Bail to the normal path for fullscreen / magnified / XR /
    // cursor-relative-offset frames so those keep their existing behavior exactly.
    private boolean canSpatialUpscale() {
        return renderer.getSurfaceWidth() > 0 && renderer.getSurfaceHeight() > 0
            && !renderer.isFullscreen()
            && renderer.getMagnifierZoom() == 1.0f
            && !renderer.isScreenOffsetYRelativeToCursor()
            && !com.winlator.star.XrActivity.isEnabled(null);
    }

    // Select the scaling mode. Modes 0/1/2 (None/Linear/Nearest) and 6 (Sharpen/CAS) are
    // NOT spatial upscalers and leave the dedicated slots null (None/Linear/Nearest are
    // handled by GLRenderer.setFilterMode; Sharpen by the existing CAS post-effect). The
    // spatial modes 3 (SGSR) / 4 (FSR) / 5 (FSR-Fit) build their pass(es) below. Sharpness
    // is 0..1 and drives SGSR EdgeSharpness / FSR RCAS at draw time. Session-live.
    public synchronized void setUpscaler(int mode, float sharpness01) {
        this.upscalerMode = mode;
        this.upscaleSharpness = sharpness01;
        upscalePrimary = null;
        upscaleSecondary = null;
        // Spatial passes: SGSR is single-pass; FSR / FSR-Fit are EASU then RCAS.
        switch (mode) {
            case 3: // SGSR
                upscalePrimary = new com.winlator.star.renderer.effects.SGSREffect();
                break;
            case 4: // FSR (fill)
            case 5: // FSR (Fit)
                // On the GL path the upscaler source is the already-letterboxed,
                // display-aspect compositor frame, so "fit" and "fill" produce the same
                // result; both run the full EASU -> RCAS FSR1 chain.
                upscalePrimary   = new com.winlator.star.renderer.effects.FSREasuEffect();
                upscaleSecondary = new com.winlator.star.renderer.effects.FSRRcasEffect();
                break;
            default:
                break;
        }
        // Mode 6 (Sharpen) reuses the existing AMD CAS post-effect; every other mode drops
        // the picker's CAS. Tracked separately so the parallel "Sharpen (CAS)" toggle is
        // never disturbed (both manage their own FSREffect instance).
        // Sharpen is snapped to 5 stops {0,25,50,75,100} in the drawer; stop 0 = OFF
        // (no CAS pass, passthrough) so only sharpness > 0 builds the picker's CAS.
        if (mode == 6) {
            if (upscaleSharpness > 0f) {
                if (pickerCasEffect == null) pickerCasEffect = buildPickerCas();
            } else if (pickerCasEffect != null) {
                removeEffect(pickerCasEffect);
                pickerCasEffect = null;
            }
        } else if (pickerCasEffect != null) {
            removeEffect(pickerCasEffect);
            pickerCasEffect = null;
        }
        renderer.xServerView.requestRender();
    }

    private Effect buildPickerCas() {
        com.winlator.star.renderer.effects.FSREffect cas = new com.winlator.star.renderer.effects.FSREffect();
        cas.setMode(com.winlator.star.renderer.effects.FSREffect.MODE_SUPER_RESOLUTION);
        // FSREffect's level scale is INVERTED: level 1 == CAS sharpness 0.90 (sharpest),
        // level 5 == 0.12 (softest). So a higher "Sharpness" slider must map to a LOWER
        // level. Invert the 0..1 slider: 1.0 -> level 1 (sharpest), 0 -> level 5 (softest).
        cas.setLevel((1.0f - upscaleSharpness) * 4.0f + 1.0f);
        addEffect(cas);
        return cas;
    }

    public synchronized void setUpscaleSharpness(float sharpness01) {
        this.upscaleSharpness = sharpness01;
        // SGSR/FSR read sharpness as a live uniform (no rebuild). The CAS shader bakes its
        // level at compile time, so for Sharpen mode (6) rebuild the picker's CAS effect to
        // apply the new snapped level -- and treat the 0 stop as OFF (no CAS pass).
        if (upscalerMode == 6) {
            if (pickerCasEffect != null) {
                removeEffect(pickerCasEffect);
                pickerCasEffect = null;
            }
            if (sharpness01 > 0f) pickerCasEffect = buildPickerCas();
        }
        renderer.xServerView.requestRender();
    }

    // Re-select a mode keeping the current sharpness (used when only the mode changes).
    public synchronized void setUpscaler(int mode) { setUpscaler(mode, this.upscaleSharpness); }

    public int getUpscalerMode() { return upscalerMode; }
    public float getUpscaleSharpness() { return upscaleSharpness; }

    // Allocate the low-res target once, lazily, at the current render-scaled surface size.
    // Like readBuffer/writeBuffer this does not reallocate on a surface-size change (the
    // game session is fixed to one orientation); the allocated dims drive the upscale math.
    private void initLowResBuffer() {
        if (lowResBuffer == null) {
            int w = Math.max(1, Math.round(renderer.getSurfaceWidth()  * RENDER_SCALE));
            int h = Math.max(1, Math.round(renderer.getSurfaceHeight() * RENDER_SCALE));
            lowResBuffer = new RenderTarget();
            lowResBuffer.allocateFramebuffer(w, h);
            lowResW = w;
            lowResH = h;
        }
    }

    private void renderUpscaled() {
        int sw = renderer.surfaceWidth;
        int sh = renderer.surfaceHeight;
        initLowResBuffer();

        // 1. Draw the scene (windows only, no cursor) into the low-res buffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lowResBuffer.getFramebuffer());
        renderer.drawWindowsScaled(RENDER_SCALE);

        // 2. Upscale low-res -> readBuffer at surface res. SGSR is single-pass; FSR runs
        //    EASU (low -> surface) then RCAS (surface -> surface, 1:1).
        if (upscaleSecondary == null) {
            bindAndClear(readBuffer.getFramebuffer(), sw, sh);
            drawUpscalePass(upscalePrimary, lowResBuffer, lowResW, lowResH, sw, sh);
        } else {
            bindAndClear(writeBuffer.getFramebuffer(), sw, sh);
            drawUpscalePass(upscalePrimary, lowResBuffer, lowResW, lowResH, sw, sh);
            bindAndClear(readBuffer.getFramebuffer(), sw, sh);
            drawUpscalePass(upscaleSecondary, writeBuffer, sw, sh, sw, sh);
        }
        // readBuffer now holds the upscaled image at surface resolution.

        // 3. Run the post-effect chain (Color/FXAA/Toon/CRT/NTSC/CAS/HDR) on top, keeping
        //    the result in readBuffer (never to screen here). Identical ping-pong to the
        //    default loop; material-less stages fall back to the blit copy.
        for (Effect effect : effects) {
            if (effect.getMaterial() == null) {
                blitReadBufferTo(writeBuffer.getFramebuffer());
                swapBuffers();
                continue;
            }
            bindAndClear(writeBuffer.getFramebuffer(), sw, sh);
            renderEffect(effect);
            swapBuffers();
        }

        // 4. Composite the final image to the screen, then draw the cursor full-res on top.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, sw, sh);
        blitReadBufferTo(0);
        renderer.drawCursorFullRes();
    }

    private void bindAndClear(int framebuffer, int w, int h) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    // Draw one upscaler pass: full-screen quad sampling `src` (input res srcW x srcH) into
    // the bound framebuffer (output res outW x outH). Shared by SGSR/EASU/RCAS; each
    // material reads whichever of resolution/srcResolution/sharpness it declares.
    private void drawUpscalePass(Effect effect, RenderTarget src,
                                 int srcW, int srcH, int outW, int outH) {
        ShaderMaterial material = effect.getMaterial();
        if (material == null) return;
        material.use();
        renderer.getQuadVertices().bind(material.programId);
        material.setUniformVec2("resolution", outW, outH);
        material.setUniformVec2("srcResolution", srcW, srcH);
        material.setUniformFloat("sharpness", upscaleSharpness);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.getTextureId());
        // The reference SGSR/EASU samplers expect CLAMP_TO_EDGE + linear filtering.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        material.setUniformInt("screenTexture", 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

}
