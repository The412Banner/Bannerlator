# NOTICE — FidelityFX Optical Flow (model 3)

win-fg "model 3" (FSR3 Optical Flow) embeds compute shaders that are an
adaptation of the **AMD FidelityFX SDK FSR3 Optical Flow** algorithm.

Source passes adapted (FidelityFX-SDK, branch `main`):

- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_prepare_luma.h`
  → `of3_luma.comp` (Rec.709 LDR luminance extraction)
- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_compute_luminance_pyramid.h`
  → `of3_downsample.comp` (4-tap box / SPD-equivalent mip reduce)
- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_compute_optical_flow_v5.h`
  → `of3_flow.comp` (SAD block-match search with coarse-level prediction)
- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_scale_optical_flow_advanced_v5.h`
  → `of3_expand.comp` (bilinear upscale of coarse flow to display resolution)

The GLSL adaptations are subgroup-free / SPD-library-free re-implementations so
they compile with `glslangValidator` for Vulkan 1.1 (Adreno/Turnip-safe). They
intentionally drop the SDK's HDR transfer-function handling, wave intrinsics
(`ffxWaveSum`), `msad4`, packed-uint luma and groupshared SAD maps, none of
which are portable to bare GLSL on this stack. See per-shader DEVIATION comments.

FidelityFX SDK is distributed under the MIT License:

```
This file is part of the FidelityFX SDK.

Copyright (C) Advanced Micro Devices, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files(the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions :

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
