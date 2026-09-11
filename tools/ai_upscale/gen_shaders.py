#!/usr/bin/env python3
"""
gen_shaders.py - turns trained AI Upscale weights into the Vulkan shaders the compositor runs.

    python3 gen_shaders.py --fast ai_upscale_4x3.json --hq ai_upscale_8x4.json \
        --out ../../app/src/main/cpp/winlator
    python3 gen_shaders.py --selftest ai_upscale_8x4.json     # reference vs the trainer's test vector

Writes, into --out:
    ai_upscale_fast.comp / ai_upscale_hq.comp   GLSL compute (weights baked in as constants)
    ai_upscale_fast_comp.h / ai_upscale_hq_comp.h / ai_upscale_final_frag.h   SPIR-V headers
The final pass shader (ai_upscale_final.frag) is hand written and lives next to them.

How the compute shader works
----------------------------
One 16x16 workgroup produces the residuals for a 16x16 block of game pixels. It loads the
block plus an L-pixel border of luma into shared memory (edge pixels repeated at the image
borders, which is exactly the replicate padding the model was trained with), then runs every
3x3 layer inside shared memory, each layer's region one pixel smaller on every side, until the
last layer lands on the 16x16 block and writes one rgba16f texel per pixel: the four sub-pixel
residuals (dx,dy) -> channel dy*2+dx. Hidden activations are stored as packed fp16 pairs
(packHalf2x16) so the largest supported network (C=8, L=4) fits in 16 KB of shared memory.

The Python reference below runs the same tiling, clamping and fp16 storage on the CPU, so the
generated code can be checked against the trainer's PyTorch output without a GPU.
"""
import os, sys, json, math, struct, argparse, subprocess, random

TILE = 16
THREADS = TILE * TILE
SHARED_LIMIT = 16384


# ----------------------------------------------------------------------------- model access
def load(path):
    d = json.load(open(path))
    assert d["format"] == "bannerlator-ai-upscale-v1", d.get("format")
    return d


def placeholder(C, L, seed=1):
    """Deterministic small random weights - only for compile tests before training lands."""
    rnd = random.Random(seed)
    ch = [1] + [C] * (L - 1) + [4]
    layers = []
    for i in range(L):
        cin, cout = ch[i], ch[i + 1]
        lay = {"cin": cin, "cout": cout,
               "w": [rnd.uniform(-0.05, 0.05) for _ in range(cout * cin * 9)],
               "b": [rnd.uniform(-0.01, 0.01) for _ in range(cout)]}
        if i < L - 1:
            lay["a"] = [0.1] * cout
        layers.append(lay)
    return {"format": "bannerlator-ai-upscale-v1", "C": C, "L": L,
            "luma": [0.2126, 0.7152, 0.0722], "layers": layers, "placeholder": True}


def W(lay, co, ci, ky, kx):
    return lay["w"][((co * lay["cin"] + ci) * 3 + ky) * 3 + kx]


def regions(L):
    return [TILE + 2 * (L - l) for l in range(L + 1)]      # R0 .. RL (RL == TILE)


def shared_layout(C, L):
    R = regions(L)
    half = C // 2                                          # uints per position (fp16 pairs)
    a = max([R[l] * R[l] * half for l in range(1, L) if l % 2 == 1] or [1])
    b = max([R[0] * R[0]] + [R[l] * R[l] * half for l in range(1, L) if l % 2 == 0])
    return a, b


def fp16(x):
    return struct.unpack("<e", struct.pack("<e", x))[0]


# ----------------------------------------------------------------------------- reference
def reference(model, lum, h, w, fp16_store=True):
    """CPU mirror of the compute shader. lum: flat row-major floats (h*w). Returns [4][h][w]."""
    C, L = model["C"], model["L"]
    lays = model["layers"]
    R = regions(L)
    out = [[0.0] * (h * w) for _ in range(4)]
    for ty in range(0, h, TILE):
        for tx in range(0, w, TILE):
            ox, oy = tx - L, ty - L
            # stage 0: luma with clamped (replicated) coordinates
            cur = [[lum[min(max(oy + y, 0), h - 1) * w + min(max(ox + x, 0), w - 1)]
                    for y in range(R[0]) for x in range(R[0])]]          # [ch][pos]
            for l in range(1, L + 1):
                lay = lays[l - 1]
                Rin, Rout = R[l - 1], R[l]
                nxt = [[0.0] * (Rout * Rout) for _ in range(lay["cout"])]
                for y in range(Rout):
                    for x in range(Rout):
                        for co in range(lay["cout"]):
                            s = lay["b"][co]
                            for ci in range(lay["cin"]):
                                src = cur[ci]
                                for ky in range(3):
                                    row = (y + ky) * Rin + x
                                    for kx in range(3):
                                        s += W(lay, co, ci, ky, kx) * src[row + kx]
                            if l < L:
                                a = lay["a"][co]
                                s = s if s > 0 else a * s
                            if fp16_store:
                                s = fp16(s)
                            nxt[co][y * Rout + x] = s
                cur = nxt
            for y in range(TILE):
                for x in range(TILE):
                    gy, gx = ty + y, tx + x
                    if gy < h and gx < w:
                        for c in range(4):
                            out[c][gy * w + gx] = cur[c][y * TILE + x]
    return out


def selftest(path):
    m = load(path)
    tv = m["test_vector"]
    h, w = tv["h"], tv["w"]
    got = reference(m, tv["lr"], h, w, fp16_store=False)
    exp = tv["res"]
    err = max(abs(got[c][i] - exp[c * h * w + i]) for c in range(4) for i in range(h * w))
    got16 = reference(m, tv["lr"], h, w, fp16_store=True)
    err16 = max(abs(got16[c][i] - exp[c * h * w + i]) for c in range(4) for i in range(h * w))
    scale = max(abs(v) for v in exp)
    print(f"{os.path.basename(path)}: C={m['C']} L={m['L']}  max|ref-torch| fp32={err:.2e}  "
          f"fp16-stored={err16:.2e}  (residual range {scale:.3f})")
    return err < 1e-5 and err16 < 2e-3


# ----------------------------------------------------------------------------- GLSL
def flt(v):
    s = "%.9g" % v
    if not any(c in s for c in ".en"):
        s += ".0"
    return s


def vec4(vals):
    return "vec4(" + ", ".join(flt(v) for v in vals) + ")"


def mat4(lay, o, i, ky, kx):
    # GLSL mat4 * vec4: result[r] = sum_c M[c][r] * v[c]; constructor fills column by column.
    vals = []
    for c in range(4):
        for r in range(4):
            vals.append(W(lay, o * 4 + r, i * 4 + c, ky, kx))
    return "mat4(" + ", ".join(flt(v) for v in vals) + ")"


def glsl(model, label):
    C, L = model["C"], model["L"]
    assert C % 4 == 0 and 4 <= C <= 8 and L >= 2, (C, L)
    lays = model["layers"]
    R = regions(L)
    half = C // 2
    sa, sb = shared_layout(C, L)
    assert (sa + sb) * 4 <= SHARED_LIMIT, ("shared memory", (sa + sb) * 4)
    ly = model["luma"]
    g = []
    e = g.append
    e("#version 450")
    # Half-precision maths (RelaxedPrecision): measured on Adreno 750 = -27% time for the 8x4
    # net at 720p, error ~0.1 of an 8-bit step. The luma tile and fp16-packed storage are unchanged.
    e("precision mediump float;")
    e(f"// Bannerlator AI Upscale ({label}): generated by tools/ai_upscale/gen_shaders.py - do not edit.")
    e(f"// Network: luma x2 residual SR, C={C} channels, L={L} 3x3 layers, "
      f"{sum(len(x['w']) + len(x['b']) + len(x.get('a', [])) for x in lays)} parameters.")
    if model.get("placeholder"):
        e("// PLACEHOLDER WEIGHTS (compile test only).")
    e("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;")
    e("layout(set = 0, binding = 0) uniform sampler2D srcTex;")
    e("layout(set = 0, binding = 1, rgba16f) uniform writeonly image2D resImg;")
    e("layout(push_constant) uniform PC { ivec2 size; } pc;")
    e(f"shared uint sA[{sa}];")
    e(f"shared uint sB[{sb}];   // also holds the luma tile (as float bits) before layer 2")
    e(f"const vec3 LUMA = vec3({flt(ly[0])}, {flt(ly[1])}, {flt(ly[2])});")
    # constants
    for l in range(1, L + 1):
        lay = lays[l - 1]
        nout = lay["cout"] // 4
        e(f"const vec4 B{l}[{nout}] = vec4[{nout}](" +
          ", ".join(vec4(lay["b"][o * 4:o * 4 + 4]) for o in range(nout)) + ");")
        if l < L:
            e(f"const vec4 A{l}[{nout}] = vec4[{nout}](" +
              ", ".join(vec4(lay["a"][o * 4:o * 4 + 4]) for o in range(nout)) + ");")
        if l == 1:
            # layer 1: one input channel -> weights as vec4 per (tap, out-group)
            e(f"const vec4 W1[{9 * nout}] = vec4[{9 * nout}](" + ", ".join(
                vec4([W(lay, o * 4 + r, 0, ky, kx) for r in range(4)])
                for ky in range(3) for kx in range(3) for o in range(nout)) + ");")
        else:
            nin = lay["cin"] // 4
            n = 9 * nout * nin
            e(f"const mat4 W{l}[{n}] = mat4[{n}](" + ", ".join(
                mat4(lay, o, i, ky, kx)
                for ky in range(3) for kx in range(3) for o in range(nout) for i in range(nin)) + ");")
    e("")
    e("vec4 prelu(vec4 v, vec4 a) { return max(v, vec4(0.0)) + a * min(v, vec4(0.0)); }")
    e("")
    e("void main() {")
    e("    uint lid = gl_LocalInvocationIndex;")
    e(f"    ivec2 org = ivec2(gl_WorkGroupID.xy) * {TILE} - {L};")
    e("    ivec2 hi = pc.size - 1;")
    e(f"    // stage 0: {R[0]}x{R[0]} luma tile, coordinates clamped (= replicate padding)")
    e(f"    for (uint p = lid; p < {R[0] * R[0]}u; p += {THREADS}u) {{")
    e(f"        int y = int(p / {R[0]}u), x = int(p % {R[0]}u);")
    e("        ivec2 c = clamp(org + ivec2(x, y), ivec2(0), hi);")
    e("        sB[p] = floatBitsToUint(dot(texelFetch(srcTex, c, 0).rgb, LUMA));")
    e("    }")
    e("    memoryBarrierShared(); barrier();")
    for l in range(1, L + 1):
        lay = lays[l - 1]
        Rin, Rout = R[l - 1], R[l]
        nout = lay["cout"] // 4
        nin = max(1, lay["cin"] // 4)
        src = "sB" if (l - 1) % 2 == 0 else "sA"          # layer 1 reads luma from sB
        dst = "sA" if l % 2 == 1 else "sB"
        last = (l == L)
        e(f"    // layer {l}: {lay['cin']} -> {lay['cout']} ch, {Rin}x{Rin} -> {Rout}x{Rout}")
        if last:
            e(f"    {{ uint y = lid / {TILE}u, x = lid % {TILE}u;")
        else:
            e(f"    for (uint p = lid; p < {Rout * Rout}u; p += {THREADS}u) {{")
            e(f"        uint y = p / {Rout}u, x = p % {Rout}u;")
        for o in range(nout):
            e(f"        vec4 a{o} = B{l}[{o}];")
        for ky in range(3):
            for kx in range(3):
                t = ky * 3 + kx
                e(f"        {{ uint q = (y + {ky}u) * {Rin}u + x + {kx}u;")
                if l == 1:
                    e(f"          float v = uintBitsToFloat({src}[q]);")
                    for o in range(nout):
                        e(f"          a{o} += W1[{t * nout + o}] * v;")
                else:
                    e(f"          uint b = q * {half}u;")
                    for i in range(nin):
                        e(f"          vec4 i{i} = vec4(unpackHalf2x16({src}[b + {2 * i}u]), "
                          f"unpackHalf2x16({src}[b + {2 * i + 1}u]));")
                    for o in range(nout):
                        terms = " + ".join(f"W{l}[{(t * nout + o) * nin + i}] * i{i}" for i in range(nin))
                        e(f"          a{o} += {terms};")
                e("        }")
        if last:
            e(f"        ivec2 gp = ivec2(gl_WorkGroupID.xy) * {TILE} + ivec2(x, y);")
            e("        if (gp.x < pc.size.x && gp.y < pc.size.y) imageStore(resImg, gp, a0);")
            e("    }")
        else:
            for o in range(nout):
                e(f"        a{o} = prelu(a{o}, A{l}[{o}]);")
            e(f"        uint d = p * {half}u;")
            for o in range(nout):
                e(f"        {dst}[d + {2 * o}u] = packHalf2x16(a{o}.xy); "
                  f"{dst}[d + {2 * o + 1}u] = packHalf2x16(a{o}.zw);")
            e("    }")
            e("    memoryBarrierShared(); barrier();")
    e("}")
    return "\n".join(g) + "\n"


def compile_spv(src_path, var, header_path, stage):
    glslang = os.environ.get("GLSLANG", "glslangValidator")
    cmd = [glslang, "-V", "-S", stage, "--target-env", "vulkan1.0", "--vn", var, "-o", header_path, src_path]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"glslang failed for {src_path}:\n{r.stdout}\n{r.stderr}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fast"); ap.add_argument("--hq")
    ap.add_argument("--placeholder", action="store_true", help="random weights (4x3 fast, 8x4 hq)")
    ap.add_argument("--out", default=".")
    ap.add_argument("--selftest", nargs="*")
    args = ap.parse_args()
    if args.selftest is not None:
        ok = all(selftest(p) for p in args.selftest)
        sys.exit(0 if ok else 1)
    fast = placeholder(4, 3, 1) if args.placeholder else load(args.fast)
    hq = placeholder(8, 4, 2) if args.placeholder else load(args.hq)
    for name, m in (("fast", fast), ("hq", hq)):
        src = os.path.join(args.out, f"ai_upscale_{name}.comp")
        with open(src, "w") as f:
            f.write(glsl(m, name))
        compile_spv(src, f"ai_upscale_{name}_code", os.path.join(args.out, f"ai_upscale_{name}_comp.h"), "comp")
        sa, sb = shared_layout(m["C"], m["L"])
        print(f"{name}: C={m['C']} L={m['L']} shared={(sa + sb) * 4} B -> {src}")
    frag = os.path.join(args.out, "ai_upscale_final.frag")
    compile_spv(frag, "ai_upscale_final_code", os.path.join(args.out, "ai_upscale_final_frag.h"), "frag")
    print("final ->", frag)


if __name__ == "__main__":
    main()
