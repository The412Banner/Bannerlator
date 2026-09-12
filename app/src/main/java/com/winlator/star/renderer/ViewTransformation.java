package com.winlator.star.renderer;

import com.winlator.star.container.Container;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    // #413 "game region": the sub-rect of the surface the game is allowed to occupy. Every
    // fullscreen mode scales the game WITHIN this region, and the region doubles as the clip/scanout
    // bounds each renderer uses to crop overflow (FILL/STRETCH) so it can never spill into the
    // on-screen-controls half. CENTER -> whole surface (so all downstream math reduces to the
    // historical output); TOP -> top half; BOTTOM -> bottom half. Same coordinate convention as
    // viewOffsetX/Y (whatever origin the consuming renderer uses), so a renderer can feed these
    // straight into its scissor/scanout dst alongside viewOffset*.
    public int regionOffsetX;
    public int regionOffsetY;
    public int regionWidth;
    public int regionHeight;

    // Legacy entry point: letterbox (preserve aspect, center with bars). Same as OFF/FIT.
    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        update(outerWidth, outerHeight, innerWidth, innerHeight, Container.FULLSCREEN_FIT, Container.ALIGN_CENTER);
    }

    // 5-arg overload: alignment defaults to CENTER (== legacy behavior) for callers that don't specify it.
    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int fullscreenMode) {
        update(outerWidth, outerHeight, innerWidth, innerHeight, fullscreenMode, Container.ALIGN_CENTER);
    }

    // Fullscreen aspect-ratio mode (#71) + handheld 50/50 split (#413). A single `aspect` scale factor
    // drives the whole mapping; fullscreenMode only chooses that scale, screenAlignment only chooses the
    // "game region" the mapping happens inside. CENTER is byte-identical to the historical output.
    //
    // #413 handheld split: on a near-square foldable panel, TOP/BOTTOM confine the game to HALF the panel
    // (the region), leaving the OTHER half free for a full-size on-screen controller. EVERY fullscreen
    // mode (OFF/FIT/FILL/INTEGER/STRETCH) now operates inside that region — the region is both the scale
    // basis (aspect is fit against the region) and the clip/scanout bounds the renderers crop to, so FILL
    // that overflows the region is cropped at the half boundary instead of spilling into the controls half.
    // CENTER's region == the whole surface, so every derived value (aspect, viewOffset*, viewWidth/Height,
    // sceneScale*, sceneOffset*) collapses to the exact historical formulas.
    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int fullscreenMode, int screenAlignment) {
        // ---- Game region (the sub-rect the game may occupy). ----
        int halfHeight = outerHeight / 2;
        switch (screenAlignment) {
            case Container.ALIGN_TOP:
                regionOffsetX = 0; regionOffsetY = 0;
                regionWidth = outerWidth; regionHeight = halfHeight;
                break;
            case Container.ALIGN_BOTTOM:
                regionOffsetX = 0; regionOffsetY = halfHeight;
                regionWidth = outerWidth; regionHeight = outerHeight - halfHeight;
                break;
            case Container.ALIGN_CENTER:
            default:
                regionOffsetX = 0; regionOffsetY = 0;
                regionWidth = outerWidth; regionHeight = outerHeight;
                break;
        }

        // Aspect is fit against the REGION (== the full surface for CENTER -> identical sx/sy).
        float sx = (float)regionWidth / innerWidth;
        float sy = (float)regionHeight / innerHeight;

        switch (fullscreenMode) {
            case Container.FULLSCREEN_FILL:
                aspect = Math.max(sx, sy);
                break;
            case Container.FULLSCREEN_INTEGER:
                aspect = Math.max(1.0f, (float)Math.floor(Math.min(sx, sy)));
                break;
            case Container.FULLSCREEN_OFF:
            case Container.FULLSCREEN_FIT:
            default:
                aspect = Math.min(sx, sy);
                break;
        }

        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        // Center the scaled game WITHIN the region. For CENTER (regionOffsetX=0, regionWidth=outerWidth)
        // this reduces EXACTLY to the historical (int)((outerWidth - innerWidth*aspect)*0.5f).
        viewOffsetX = regionOffsetX + (int)((regionWidth - innerWidth * aspect) * 0.5f);

        // sceneScale* stay relative to the FULL surface (unchanged) — a smaller aspect from a half region
        // auto-shrinks these, so the scene render + touch map track the region for free.
        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;

        // Vertical placement. CENTER reproduces the EXACT historical formulas (do NOT touch). TOP/BOTTOM
        // center the scaled game inside their region via the same arithmetic; sceneOffsetY is kept
        // consistent with viewOffsetY in guest units (viewOffsetY * innerHeight/outerHeight) so the scene
        // render + touch map stay aligned — that general form equals sceneGapY*0.5f at CENTER, but CENTER
        // keeps its own historical line so the output is byte-identical.
        float sceneGapY = innerHeight - innerHeight * sceneScaleY;
        viewOffsetY = regionOffsetY + (int)((regionHeight - innerHeight * aspect) * 0.5f);
        switch (screenAlignment) {
            case Container.ALIGN_TOP:
            case Container.ALIGN_BOTTOM:
                sceneOffsetY = viewOffsetY * (float)innerHeight / outerHeight;
                break;
            case Container.ALIGN_CENTER:
            default:
                sceneOffsetY = sceneGapY * 0.5f;
                break;
        }
    }
}
