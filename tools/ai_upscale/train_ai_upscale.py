#!/usr/bin/env python3
"""
train_ai_upscale.py - trains the model behind Bannerlator's "AI" / "AI HQ" scaling modes.

What the model does
-------------------
It doubles the resolution of the brightness (luma) channel of a game frame. A plain bilinear
enlargement is computed first; the network predicts the detail that enlargement loses (one
residual value per output pixel) and the app adds it back on the GPU. Colour comes from the
bilinear enlargement unchanged, so the network never shifts hues.

Data
----
Our own Bannerlator frame captures (the win-fg `.wfgcap` dataset: 256x256 patches cut from
1280x720 game frames on the device). Only the centre frame of each patch triplet is used.
Trained from scratch: no third-party weights, no third-party data.

Architecture (MUST match gen_shaders.py and the shipped compute shader exactly)
--------------------------------------------------------------------------------
    x   = replicate_pad(luma_lr, L)               # L = number of 3x3 convs
    x   = conv3x3(x) -> PReLU  ... (L-1 times)    # valid convs, channels 1 -> C -> ... -> C
    x   = conv3x3(x)                              # C -> 4, no activation
    res = pixel_shuffle(x, 2)                     # sub-pixel (dx, dy) = channel dy*2 + dx
    out = bilinear_x2(luma_lr) + res              # align_corners=False, edges clamped
Luma = 0.2126 R + 0.7152 G + 0.0722 B on the stored (gamma-encoded) values, like the shader.

Outputs (per config "CxL", e.g. 8x4)
------------------------------------
    ai_upscale_<C>x<L>.json   weights + validation scores + a numeric test vector
    samples_<C>x<L>.png       luma strips: nearest | bilinear | bicubic | model | original
    summary.json              every config's scores in one place
"""
import os, sys, json, time, math, random, argparse
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from wfgcap_dataset import load_index, _make_decoder

LUMA = (0.2126, 0.7152, 0.0722)
PATCH = 256


# ----------------------------------------------------------------------------- data
def _decode_chunk(args):
    """Worker: decode one shard's worth of samples into uint8 luma centre frames."""
    data, shard, items = args
    dec, _ = _make_decoder()
    w = np.asarray(LUMA, np.float32)
    out = np.empty((len(items), PATCH, PATCH), np.uint8)
    with open(os.path.join(data, shard), "rb") as fh:
        for i, (off, ln) in enumerate(items):
            fh.seek(off)
            arr = dec(fh.read(ln))                       # 256 x 768 x 4
            cen = arr[:, PATCH:2 * PATCH, :3].astype(np.float32)
            out[i] = np.clip(np.rint(cen @ w), 0, 255).astype(np.uint8)
    return out


def resolve_data_dir(d):
    """Kaggle may mount the dataset below /kaggle/input/<slug>/...: find the folder that holds it."""
    import glob
    if os.path.exists(os.path.join(d, "manifest.jsonl")):
        return d
    for base in (d, "/kaggle/input", "."):
        hits = glob.glob(os.path.join(base, "**", "manifest.jsonl"), recursive=True)
        if hits:
            print(f"  dataset folder: {os.path.dirname(hits[0])}", flush=True)
            return os.path.dirname(hits[0])
    raise FileNotFoundError(f"no manifest.jsonl under {d} or /kaggle/input")


def load_luma(data, curated_json, limit, seed, workers):
    idx = load_index(data, curated_json)
    samples = [s for s in idx["samples"] if s["len"] >= 8001]
    if limit and len(samples) > limit:
        samples = random.Random(seed).sample(samples, limit)
    by_shard = {}
    for s in samples:
        by_shard.setdefault(s["shard"], []).append((s["off"], s["len"]))
    jobs = [(data, sh, sorted(v)) for sh, v in sorted(by_shard.items())]
    t0 = time.time()
    with ProcessPoolExecutor(max_workers=workers) as ex:
        parts = list(ex.map(_decode_chunk, jobs))
    arr = np.concatenate(parts, 0)
    print(f"  loaded {arr.shape[0]} frames from {len(jobs)} shards in {time.time()-t0:.0f}s", flush=True)
    return arr


# ----------------------------------------------------------------------------- model
class AiUpscale(nn.Module):
    def __init__(self, C, L):
        super().__init__()
        assert L >= 2 and C % 4 == 0
        self.C, self.L = C, L
        ch = [1] + [C] * (L - 1) + [4]
        self.convs = nn.ModuleList(nn.Conv2d(ch[i], ch[i + 1], 3, padding=0) for i in range(L))
        self.acts = nn.ModuleList(nn.PReLU(C, init=0.1) for _ in range(L - 1))
        nn.init.normal_(self.convs[-1].weight, std=1e-3)
        nn.init.zeros_(self.convs[-1].bias)

    def features(self, y, fp16_act=False):
        """LR luma (B,1,h,w) -> raw 4-channel residual field (B,4,h,w)."""
        x = F.pad(y, (self.L,) * 4, mode="replicate")
        for i, c in enumerate(self.convs):
            x = c(x)
            if i < self.L - 1:
                x = self.acts[i](x)
            if fp16_act:                           # the shader stores activations and the
                x = x.half().float()               # residual image as fp16
        return x

    def forward(self, y, fp16_act=False):
        res = F.pixel_shuffle(self.features(y, fp16_act), 2)
        base = F.interpolate(y, scale_factor=2, mode="bilinear", align_corners=False)
        return base + res

    def macs_per_lr_pixel(self):
        ch = [1] + [self.C] * (self.L - 1) + [4]
        return sum(9 * ch[i] * ch[i + 1] for i in range(self.L))


# ----------------------------------------------------------------------------- degradations
def downscale(hr, mode):
    if mode == "box":
        return F.avg_pool2d(hr, 2)
    return F.interpolate(hr, scale_factor=0.5, mode="bicubic", antialias=True,
                         align_corners=False).clamp(0, 1)


def make_batch(frames, B, crop, dev, gen):
    N = frames.shape[0]
    idx = torch.randint(0, N, (B,), device=dev, generator=gen)
    ox = torch.randint(0, (PATCH - crop) // 2 + 1, (B,), device=dev, generator=gen) * 2
    oy = torch.randint(0, (PATCH - crop) // 2 + 1, (B,), device=dev, generator=gen) * 2
    ar = torch.arange(crop, device=dev)
    rows = (oy[:, None] + ar[None, :])[:, :, None]
    cols = (ox[:, None] + ar[None, :])[:, None, :]
    hr = frames[idx[:, None, None], rows, cols].float().div_(255.0)[:, None]   # B,1,crop,crop
    # flips / transpose (8 dihedral variants, applied per half-batch to stay cheap)
    if torch.rand(1, generator=gen, device=dev).item() < 0.5:
        hr = hr.flip(3)
    if torch.rand(1, generator=gen, device=dev).item() < 0.5:
        hr = hr.flip(2)
    if torch.rand(1, generator=gen, device=dev).item() < 0.5:
        hr = hr.transpose(2, 3)
    # 70% box (keeps game aliasing), 30% bicubic-antialiased (smooth sources)
    use_box = torch.rand(B, 1, 1, 1, device=dev, generator=gen) < 0.7
    lr = torch.where(use_box, downscale(hr, "box"), downscale(hr, "bicubic"))
    return lr, hr


# ----------------------------------------------------------------------------- eval
def psnr(a, b):
    mse = F.mse_loss(a.clamp(0, 1), b, reduction="none").mean(dim=(1, 2, 3))
    return (10 * torch.log10(1.0 / mse.clamp_min(1e-12))).mean().item()


@torch.no_grad()
def evaluate(model, val, dev, mode="box", fp16_act=False, bs=64):
    model.eval()
    tot = {"model": 0.0, "bilinear": 0.0, "bicubic": 0.0}
    n = 0
    for i in range(0, val.shape[0], bs):
        hr = torch.from_numpy(val[i:i + bs]).to(dev).float().div_(255.0)[:, None]
        lr = downscale(hr, mode)
        k = hr.shape[0]
        tot["model"] += psnr(model(lr, fp16_act), hr) * k
        tot["bilinear"] += psnr(F.interpolate(lr, scale_factor=2, mode="bilinear", align_corners=False), hr) * k
        tot["bicubic"] += psnr(F.interpolate(lr, scale_factor=2, mode="bicubic", align_corners=False), hr) * k
        n += k
    model.train()
    return {k: v / n for k, v in tot.items()}


@torch.no_grad()
def save_samples(model, val, dev, path, picks=(3, 500, 1200, 2000)):
    from PIL import Image
    model.eval()
    rows = []
    for p in picks:
        p = min(p, val.shape[0] - 1)
        hr = torch.from_numpy(val[p:p + 1]).to(dev).float().div_(255.0)[:, None]
        lr = downscale(hr, "box")
        tiles = [F.interpolate(lr, scale_factor=2, mode="nearest"),
                 F.interpolate(lr, scale_factor=2, mode="bilinear", align_corners=False),
                 F.interpolate(lr, scale_factor=2, mode="bicubic", align_corners=False),
                 model(lr), hr]
        rows.append(torch.cat([t.clamp(0, 1) for t in tiles], 3))
    img = torch.cat(rows, 2)[0, 0].mul(255).round().byte().cpu().numpy()
    Image.fromarray(img).save(path)
    model.train()


# ----------------------------------------------------------------------------- export
def export(model, scores, val, dev, path, extra):
    layers = []
    for i, c in enumerate(model.convs):
        L = {"cin": c.in_channels, "cout": c.out_channels,
             "w": c.weight.detach().cpu().double().flatten().tolist(),     # [cout][cin][ky][kx]
             "b": c.bias.detach().cpu().double().tolist()}
        if i < model.L - 1:
            L["a"] = model.acts[i].weight.detach().cpu().double().tolist()
        layers.append(L)
    # Numeric test vector: an odd-sized LR crop treated as a whole image (exercises the
    # replicate padding and partial 16x16 tiles), plus the raw 4-channel residual field.
    with torch.no_grad():
        hr = torch.from_numpy(val[7:8]).to(dev).float().div_(255.0)[:, None]
        lr = downscale(hr, "box")[:, :, 11:11 + 29, 5:5 + 37].contiguous()   # 29 x 37
        res = model.features(lr)
    tv = {"h": 29, "w": 37,
          "lr": lr[0, 0].cpu().double().flatten().tolist(),
          "res": res[0].cpu().double().flatten().tolist()}                     # [4][h][w]
    doc = {"format": "bannerlator-ai-upscale-v1", "C": model.C, "L": model.L,
           "luma": list(LUMA), "scale": 2,
           "macs_per_lr_pixel": model.macs_per_lr_pixel(),
           "params": sum(p.numel() for p in model.parameters()),
           "layers": layers, "scores": scores, "test_vector": tv}
    doc.update(extra)
    with open(path, "w") as f:
        json.dump(doc, f)


# ----------------------------------------------------------------------------- train
def train_one(C, L, frames, val, args, dev):
    torch.manual_seed(args.seed)
    gen = torch.Generator(device=dev); gen.manual_seed(args.seed)
    model = AiUpscale(C, L).to(dev)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warm) *
        (0.02 + 0.98 * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps)))))
    name = f"{C}x{L}"
    print(f"== {name}: params={sum(p.numel() for p in model.parameters())} "
          f"macs/lr-px={model.macs_per_lr_pixel()}", flush=True)
    best, best_state, t0 = -1.0, None, time.time()
    for step in range(1, args.steps + 1):
        lr, hr = make_batch(frames, args.bs, args.crop, dev, gen)
        loss = F.l1_loss(model(lr), hr)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step(); sched.step()
        if step % args.eval_every == 0 or step == args.steps:
            sc = evaluate(model, val, dev, "box")
            print(f"  {name} step {step} loss {loss.item():.5f} | val box: model {sc['model']:.3f} "
                  f"bilinear {sc['bilinear']:.3f} bicubic {sc['bicubic']:.3f} dB ({time.time()-t0:.0f}s)",
                  flush=True)
            if sc["model"] > best:
                best = sc["model"]
                best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
    model.load_state_dict(best_state)
    scores = {"box": evaluate(model, val, dev, "box"),
              "bicubic_lr": evaluate(model, val, dev, "bicubic"),
              "box_fp16_activations": evaluate(model, val, dev, "box", fp16_act=True)}
    print(f"  {name} FINAL {json.dumps(scores)}", flush=True)
    export(model, scores, val, dev, os.path.join(args.out, f"ai_upscale_{name}.json"),
           {"train_steps": args.steps, "train_frames": int(frames.shape[0]),
            "val_frames": int(val.shape[0]), "seconds": round(time.time() - t0)})
    save_samples(model, val, dev, os.path.join(args.out, f"samples_{name}.png"))
    return scores


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", default="./out")
    ap.add_argument("--configs", default="4x3,4x4,8x3,8x4")
    ap.add_argument("--steps", type=int, default=50000)
    ap.add_argument("--bs", type=int, default=128)
    ap.add_argument("--crop", type=int, default=96)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--eval-every", type=int, default=5000)
    ap.add_argument("--train-limit", type=int, default=48000)
    ap.add_argument("--workers", type=int, default=max(1, os.cpu_count() or 1))
    ap.add_argument("--seed", type=int, default=1234)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print("device", dev, torch.cuda.get_device_name(0) if dev == "cuda" else "", flush=True)

    args.data = resolve_data_dir(args.data)
    ctrain = os.path.join(args.data, "curated_train.json")
    cval = os.path.join(args.data, "curated_val.json")
    print("loading val ...", flush=True)
    val = load_luma(args.data, cval, 0, args.seed, args.workers)
    print("loading train ...", flush=True)
    train = load_luma(args.data, ctrain, args.train_limit, args.seed, args.workers)
    frames = torch.from_numpy(train).to(dev)
    del train

    summary = {}
    for cfg in args.configs.split(","):
        C, L = (int(v) for v in cfg.lower().split("x"))
        summary[cfg] = train_one(C, L, frames, val, args, dev)
        with open(os.path.join(args.out, "summary.json"), "w") as f:
            json.dump(summary, f, indent=1)
    print("SUMMARY", json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()
