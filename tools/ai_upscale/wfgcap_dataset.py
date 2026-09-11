"""
wfgcap_dataset.py — PyTorch Dataset for Bannerlator win-fg .wfgcap captures.

Format (self-describing via manifest.jsonl header record):
  format = "qoi-rgba8", mode = "patch"
  Each manifest record = one source frame-triplet, with `patches_per_triplet`
  spatial crops. Each patch = a QOI-encoded RGBA image of size (3*P) x P laid
  out [prev | center | next] left-to-right. CENTER is the interpolation target.
  patch["off"], patch["len"] index the QOI blob inside patch["shard"].

Yields (prev, next, center) as float CHW tensors in [0,1]:
    model(prev, next) -> predicts center.

QOI decode backend priority: `qoi` pip pkg (C, fast) -> Pillow>=10.4 -> pure-py.
On Kaggle:  pip install qoi   (add to the notebook's first cell).
"""
import os, json, struct
from typing import List, Dict, Optional

try:
    import numpy as np
except ImportError:  # allow importing the pure-py fallback without numpy for smoke tests
    np = None


# ---------------------------------------------------------------- QOI decode
def _qoi_decode_purepy(buf: bytes):
    assert buf[:4] == b"qoif", "bad qoi magic"
    w, h, ch, cs = struct.unpack(">IIBB", buf[4:14])
    px_len = w * h * 4
    out = bytearray(px_len)
    index = [(0, 0, 0, 0)] * 64
    r, g, b, a = 0, 0, 0, 255
    p, op = 14, 0
    while op < px_len:
        b0 = buf[p]; p += 1
        if b0 == 0xFE:
            r, g, b = buf[p], buf[p+1], buf[p+2]; p += 3
        elif b0 == 0xFF:
            r, g, b, a = buf[p], buf[p+1], buf[p+2], buf[p+3]; p += 4
        else:
            tag = b0 & 0xC0
            if tag == 0x00:
                r, g, b, a = index[b0 & 0x3F]
            elif tag == 0x40:
                r = (r + ((b0 >> 4) & 3) - 2) & 0xFF
                g = (g + ((b0 >> 2) & 3) - 2) & 0xFF
                b = (b + (b0 & 3) - 2) & 0xFF
            elif tag == 0x80:
                b1 = buf[p]; p += 1
                vg = (b0 & 0x3F) - 32
                r = (r + vg - 8 + ((b1 >> 4) & 0x0F)) & 0xFF
                g = (g + vg) & 0xFF
                b = (b + vg - 8 + (b1 & 0x0F)) & 0xFF
            else:
                run = (b0 & 0x3F)
                for _ in range(run + 1):
                    out[op:op+4] = bytes((r, g, b, a)); op += 4
                index[(r*3 + g*5 + b*7 + a*11) % 64] = (r, g, b, a)
                continue
        index[(r*3 + g*5 + b*7 + a*11) % 64] = (r, g, b, a)
        out[op:op+4] = bytes((r, g, b, a)); op += 4
    return w, h, out


def _make_decoder():
    """Return fn(bytes)->(H,W,4) uint8 ndarray, fastest available backend.
    Priority: qoi (C, offline-installed from side dataset) -> Pillow (if it can
    actually decode QOI) -> pure-py fallback. Each candidate is probed once
    against a minimal QOI blob so we never silently pick a broken backend."""
    import io
    # 4x4 RGBA test QOI: header + one RGBA op + RUN of 15 (0xC0|14=0xCE) + end marker
    probe = (b"qoif" + struct.pack(">IIBB", 4, 4, 4, 0)
             + b"\xff\x10\x20\x30\xff" + b"\xce" + b"\x00"*7 + b"\x01")

    try:
        import qoi as _qoi
        a = _qoi.decode(probe)
        if getattr(a, "shape", None) == (4, 4, 4):
            return (lambda buf: _qoi.decode(buf)), "qoi-pkg"
    except Exception:
        pass
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(probe)); im.load()
        def dec(buf):
            return np.asarray(Image.open(io.BytesIO(buf)).convert("RGBA"), dtype=np.uint8)
        _ = dec(probe)
        return dec, "pillow"
    except Exception:
        pass
    def dec(buf):
        w, h, raw = _qoi_decode_purepy(buf)
        return np.frombuffer(bytes(raw), dtype=np.uint8).reshape(h, w, 4)
    return dec, "pure-py"


# ---------------------------------------------------------------- index build
def load_index(session_dir: str, curated_json: Optional[str] = None) -> Dict:
    """Parse manifest.jsonl (or a curated index) -> {header, samples:[...]}.
    Each sample = dict(shard, off, len, seq, motion, patch_idx)."""
    if curated_json and os.path.exists(curated_json):
        with open(curated_json) as f:
            return json.load(f)
    header = None
    samples: List[Dict] = []
    with open(os.path.join(session_dir, "manifest.jsonl")) as f:
        for line in f:
            j = json.loads(line)
            if j.get("record") == "consent":
                continue
            if j.get("win_fg_capture") == 1:
                header = j; continue
            if "seq" not in j:
                continue
            for pi, pt in enumerate(j["patches"]):
                samples.append(dict(shard=j["shard"], off=pt["off"], len=pt["len"],
                                    seq=j["seq"], motion=j.get("motion", 0.0), patch_idx=pi))
    return dict(header=header, samples=samples)


# ---------------------------------------------------------------- Dataset
try:
    import torch
    from torch.utils.data import Dataset
    _HAS_TORCH = True
except Exception:
    _HAS_TORCH = False
    Dataset = object  # type: ignore


class WfgCapDataset(Dataset):
    """Random-access triplet dataset. Keeps one file handle per shard per worker."""

    def __init__(self, session_dir: str, index: Optional[Dict] = None,
                 curated_json: Optional[str] = None, patch: int = 256,
                 augment: bool = True, min_motion: float = 0.0,
                 min_len: int = 8001):
        self.session_dir = session_dir
        self.patch = patch
        self.augment = augment
        idx = index or load_index(session_dir, curated_json)
        self.header = idx["header"]
        self.samples = [s for s in idx["samples"]
                        if s["motion"] >= min_motion and s["len"] >= min_len]
        self._decode, self.backend = _make_decoder()
        self._fh: Dict[str, object] = {}

    def __len__(self):
        return len(self.samples)

    def _handle(self, shard):
        fh = self._fh.get(shard)
        if fh is None:
            fh = open(os.path.join(self.session_dir, shard), "rb")
            self._fh[shard] = fh
        return fh

    def _read_triplet(self, s):
        fh = self._handle(s["shard"])
        fh.seek(s["off"])
        blob = fh.read(s["len"])
        arr = self._decode(blob)                 # H x (3P) x 4 uint8
        P = self.patch
        prev = arr[:, 0:P, :3]
        cen  = arr[:, P:2*P, :3]
        nxt  = arr[:, 2*P:3*P, :3]
        return prev, cen, nxt

    def __getitem__(self, i):
        s = self.samples[i]
        prev, cen, nxt = self._read_triplet(s)
        if self.augment:
            if np.random.rand() < 0.5:                       # horizontal flip
                prev, cen, nxt = prev[:, ::-1], cen[:, ::-1], nxt[:, ::-1]
            if np.random.rand() < 0.5:                       # vertical flip
                prev, cen, nxt = prev[::-1], cen[::-1], nxt[::-1]
            if np.random.rand() < 0.5:                       # temporal reverse
                prev, nxt = nxt, prev
        to = lambda x: torch.from_numpy(np.ascontiguousarray(
            x.transpose(2, 0, 1))).float().div_(255.0)
        return to(prev), to(nxt), to(cen)


if __name__ == "__main__":
    # Smoke test against the real capture (no torch needed for index/decode).
    import sys
    sess = sys.argv[1] if len(sys.argv) > 1 else \
        "/storage/emulated/0/Download/win-fg/session_1788126027889"
    idx = load_index(sess)
    print("header:", json.dumps(idx["header"]))
    print("samples:", len(idx["samples"]))
    dec, backend = _make_decoder()
    print("decode backend:", backend)
    s = idx["samples"][15]
    with open(os.path.join(sess, s["shard"]), "rb") as f:
        f.seek(s["off"]); blob = f.read(s["len"])
    arr = dec(blob)
    print("decoded shape:", getattr(arr, "shape", None), "seq", s["seq"], "motion", s["motion"])
