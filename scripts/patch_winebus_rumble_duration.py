#!/usr/bin/env python3
"""
Patch Wine's winebus.so so SDL rumble does not auto-expire after the short
duration Wine passes through from the guest force-feedback path
("TideGear #91 / preload-free winebus duration patch", re-derived for
Bannerlator's own Proton arm64ec builds).

winebus' sdl_device_haptics_start() drives SDL_JoystickRumble and
SDL_JoystickRumbleTriggers through pointers it dlsym'd out of SDL2, so each
rumble call is INDIRECT. SDL2 auto-stops the effect after the caller-supplied
duration_ms (~1s here), which is what we defeat by forcing that 4th arg to
0xffffffff (-1, ~50 days).

aarch64-unix  (VERIFIED against three real arm64ec winebus.so builds)
---------------------------------------------------------------------
Bannerlator's builds set the 4th integer arg from a saved register, NOT from a
stack slot the way BannerHub's GameHub build does (that one emits
`ldur w3,[x29,#-0x14]`). At BOTH call sites inside sdl_device_haptics_start:

    ; SDL_JoystickRumble            |  ; SDL_JoystickRumbleTriggers (guarded by cbz x8)
    ldr  x8,[x8,#off]    ; ptr      |  ldr  x8,[x8,#off]    ; ptr
    mov  w2,wA           ; arg3     |  mov  w2,wB           ; arg3
    mov  w3,wN           ; arg4 dur |  mov  w3,wN           ; arg4 dur   <-- target
    blr  x8              ; indirect |  blr  x8              ; indirect

The 8-byte window {mov w3,wN ; blr x8} (mov = ORR-shifted-reg into w3) matches
EXACTLY the 2 rumble sites in each build. We rewrite `mov w3,wN` -> `mov w3,#-1`
(movn w3,#0 = 0x12800003 = 03 00 80 12), leaving `blr x8` intact. Exact
per-build fingerprints (source register differs by build):

    Proton 9.0 arm64ec (bundled, 220176 B): mov w3,w20  e3 03 14 2a 00 01 3f d6  x2
    Proton 10.0-x arm64ec (content pack):   mov w3,w19  e3 03 13 2a 00 01 3f d6  x2
    Proton 11.0-x arm64ec (content pack):   mov w3,w19  e3 03 13 2a 00 01 3f d6  x2

P10 and P11 share identical rumble-site bytes; each exact pattern matches its 2
sites and 0 in the other builds (cross-checked).

Build-agnostic structural fallback: to avoid hand-deriving a pattern for every
future Proton / GE variant / imported wrapper, if none of the exact patterns
matches exactly 2 sites we scan for the masked shape {mov w3,w<0..30> ; blr x8}
(source register wildcarded, wzr=31 excluded so the zero-duration stop shape is
never touched) and apply it ONLY when it matches EXACTLY 2 sites. This same shape
yields exactly 2 sites on P9/P10/P11. Residual risk (accepted): a build with two
unrelated `mov w3,w<reg>; blr x8` would be mis-patched; exact patterns are tried
first and the ==2 guard is the mitigation.

x86_64-unix  (VERIFIED against Wine 10.0 x86_64 winebus.so, 78504 bytes,
             content pack Wine/10.0-X86_64-1)
------------------------------------------------------------------------
System V ABI -> 4th int arg = ECX (duration_ms). At BOTH rumble sites inside
sdl_device_haptics_start (this build is -O0):

    8B 4D E4          mov   ecx, [rbp-0x1c]   ; duration_ms (4th arg)   <-- target
    0F B7 F6          movzwl %si, %esi
    0F B7 D2          movzwl %dx, %edx
    FF D0             call  *%rax             ; indirect SDL_JoystickRumble[Triggers]

The exact 11-byte window matches EXACTLY the 2 rumble sites; the distinctive
suffix `movzwl si; movzwl dx; call *rax` (0F B7 F6 0F B7 D2 FF D0) occurs only
there. We replace `mov ecx,[rbp-0x1c]` (8B 4D E4) with `or ecx,-1` (83 C9 FF)
so ECX becomes 0xffffffff; suffix/call preserved. The zero-duration
sdl_device_haptics_stop materializes ecx with `xor ecx,ecx` (31 C9), so it is
NOT matched and stays untouched. A masked structural fallback (disp8 floats:
8B 4D ?? + the exact suffix) covers a stack-slot shift in another build and
still matches exactly 2 here.

Guard on both arches: an exact pattern matching exactly 2 sites -> patch; else
a structural fallback if it yields exactly 2; else ambiguous -> SKIP. Already
patched (2 patched windows) -> no-op. Never partial/destructive. Zero-duration
stop paths are separate call sites and stay untouched.
"""
import argparse
import shutil
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# aarch64 (verified)
# ---------------------------------------------------------------------------

AARCH64_INDIRECT_CALL_X8       = bytes.fromhex("00 01 3f d6")  # blr x8
AARCH64_PATCHED_DURATION_LOAD  = bytes.fromhex("03 00 80 12")  # mov w3, #-1 (movn w3,#0)
AARCH64_PATCHED_SITE  = AARCH64_PATCHED_DURATION_LOAD + AARCH64_INDIRECT_CALL_X8

# Exact per-build fingerprints: (name, "mov w3,wN") -> full "mov w3,wN ; blr x8" window.
AARCH64_EXACT_SIGS = [
    ("Proton 10/11 (mov w3,w19)", bytes.fromhex("e3 03 13 2a")),
    ("Proton 9.0 (mov w3,w20)",   bytes.fromhex("e3 03 14 2a")),
]


def _aarch64_structural_sites(blob: bytes) -> list:
    """Offsets of 'mov w3,w<0..30> ; blr x8' (ORR-shifted-reg into w3, wzr excluded)."""
    hits = []
    n = len(blob)
    for i in range(n - 8 + 1):
        if blob[i] != 0xe3 or blob[i + 1] != 0x03 or blob[i + 3] != 0x2a:
            continue
        rm = blob[i + 2]
        if rm & 0xe0:            # bits 23:21 must be 0 (plain LSL#0 ORR)
            continue
        if (rm & 0x1f) == 0x1f:  # exclude wzr (zero duration / stop shape)
            continue
        if blob[i + 4:i + 8] == AARCH64_INDIRECT_CALL_X8:
            hits.append(i)
    return hits


# ---------------------------------------------------------------------------
# x86_64 (VERIFIED against Wine 10.0 x86_64 winebus.so).
# ---------------------------------------------------------------------------

# movzwl si,esi ; movzwl dx,edx ; call *rax  -- the distinctive rumble-arg suffix.
X86_64_SUFFIX          = bytes.fromhex("0f b7 f6  0f b7 d2  ff d0")
# Exact per-build window: mov ecx,[rbp-0x1c] ; <suffix>  ->  or ecx,-1 ; <suffix>
X86_64_ORIGINAL_SITE   = bytes.fromhex("8b 4d e4") + X86_64_SUFFIX
X86_64_PATCHED_SITE    = bytes.fromhex("83 c9 ff") + X86_64_SUFFIX  # or ecx,-1 ; <suffix>
X86_64_PATCHED_LOAD    = bytes.fromhex("83 c9 ff")                  # or ecx, -1
# Structural fallback (disp8 floats): 8B 4D ?? + suffix.
X86_64_STRUCT_PATTERN  = (bytes.fromhex("8b 4d 00") + X86_64_SUFFIX,
                          bytes.fromhex("ff ff 00") + b"\xff" * len(X86_64_SUFFIX))


ELF_MACHINE_AARCH64 = 0xb7
ELF_MACHINE_X86_64  = 0x3e


def elf_machine(blob: bytes) -> int:
    return int.from_bytes(blob[18:20], "little")


def find_all(blob: bytes, needle: bytes) -> list:
    hits = []
    start = 0
    while True:
        pos = blob.find(needle, start)
        if pos < 0:
            return hits
        hits.append(pos)
        start = pos + 1


def find_all_masked(blob: bytes, pattern: bytes, mask: bytes) -> list:
    assert len(pattern) == len(mask)
    n = len(pattern)
    hits = []
    for i in range(len(blob) - n + 1):
        ok = True
        for j in range(n):
            if mask[j] and blob[i + j] != pattern[j]:
                ok = False
                break
        if ok:
            hits.append(i)
    return hits


def winebus_targets(path: Path) -> list:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(path.rglob("winebus.so"))
    raise FileNotFoundError(path)


def _aarch64_apply(path: Path, blob: bytes, sites: list, how: str, *, dry_run: bool) -> bool:
    print(f"PATCH (aarch64, {how}): {path}")
    for off in sites:
        print(f"  file+{off:#x}: mov w3, w<reg> -> mov w3, #-1")
    if dry_run:
        return True
    mutable = bytearray(blob)
    for off in sites:
        mutable[off:off + len(AARCH64_PATCHED_DURATION_LOAD)] = AARCH64_PATCHED_DURATION_LOAD
    path.write_bytes(mutable)
    verify = path.read_bytes()
    if len(find_all(verify, AARCH64_PATCHED_SITE)) != 2:
        raise RuntimeError(f"{path}: aarch64 verification failed after write")
    return True


def patch_aarch64(path: Path, blob: bytes, *, dry_run: bool) -> bool:
    patched_hits = find_all(blob, AARCH64_PATCHED_SITE)
    if len(patched_hits) == 2:
        print(f"OK: {path} already patched (aarch64) at "
              f"{', '.join(hex(x) for x in patched_hits)}")
        return False

    # 1) Exact per-build patterns: apply the FIRST that matches exactly 2 sites.
    for name, mov in AARCH64_EXACT_SIGS:
        hits = find_all(blob, mov + AARCH64_INDIRECT_CALL_X8)
        if len(hits) == 2:
            return _aarch64_apply(path, blob, hits, name, dry_run=dry_run)

    # 2) Build-agnostic structural fallback: only if it matches exactly 2 sites.
    sites = _aarch64_structural_sites(blob)
    if len(sites) == 2:
        return _aarch64_apply(path, blob, sites, "structural fallback", dry_run=dry_run)

    raise ValueError(
        f"{path}: no exact pattern matched 2 sites and structural fallback found "
        f"{len(sites)} (patched={len(patched_hits)}) - ambiguous/unknown, skipped"
    )


def _x86_64_apply(path: Path, blob: bytes, sites: list, how: str, *, dry_run: bool) -> bool:
    print(f"PATCH (x86_64, {how}): {path}")
    for off in sites:
        print(f"  file+{off:#x}: mov ecx,[rbp+disp8] -> or ecx,-1")
    if dry_run:
        return True
    mutable = bytearray(blob)
    for off in sites:
        mutable[off:off + len(X86_64_PATCHED_LOAD)] = X86_64_PATCHED_LOAD
    path.write_bytes(mutable)
    verify = path.read_bytes()
    if len(find_all(verify, X86_64_PATCHED_SITE)) != 2:
        raise RuntimeError(f"{path}: x86_64 verification failed after write")
    return True


def patch_x86_64(path: Path, blob: bytes, *, dry_run: bool) -> bool:
    patched_hits = find_all(blob, X86_64_PATCHED_SITE)
    if len(patched_hits) == 2:
        print(f"OK: {path} already patched (x86_64) at "
              f"{', '.join(hex(x) for x in patched_hits)}")
        return False

    # 1) Exact per-build window.
    hits = find_all(blob, X86_64_ORIGINAL_SITE)
    if len(hits) == 2:
        return _x86_64_apply(path, blob, hits, "Wine 10.0 (mov ecx,[rbp-0x1c])", dry_run=dry_run)

    # 2) Structural fallback: mov ecx,[rbp+disp8] + distinctive suffix (disp8 wildcarded).
    sites = find_all_masked(blob, *X86_64_STRUCT_PATTERN)
    if len(sites) == 2:
        return _x86_64_apply(path, blob, sites, "structural fallback", dry_run=dry_run)

    raise ValueError(
        f"{path}: no exact x86_64 pattern matched 2 sites and structural fallback found "
        f"{len(sites)} (patched={len(patched_hits)}) - ambiguous/unknown, skipped"
    )


def patch_one(path: Path, *, backup: bool, dry_run: bool) -> bool:
    blob = path.read_bytes()
    if not blob.startswith(b"\x7fELF"):
        raise ValueError(f"{path}: not an ELF file")
    if b"SDL_JoystickRumble" not in blob:
        raise ValueError(f"{path}: SDL_JoystickRumble string not found")

    machine = elf_machine(blob)
    if not dry_run and backup:
        backup_path = path.with_suffix(path.suffix + ".bak")
        if not backup_path.exists():
            shutil.copy2(path, backup_path)
            print(f"  backup: {backup_path}")

    if machine == ELF_MACHINE_AARCH64:
        return patch_aarch64(path, blob, dry_run=dry_run)
    if machine == ELF_MACHINE_X86_64:
        return patch_x86_64(path, blob, dry_run=dry_run)
    raise ValueError(f"{path}: unsupported e_machine=0x{machine:x}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "paths",
        nargs="+",
        type=Path,
        help="winebus.so file(s), or directories to search recursively",
    )
    parser.add_argument(
        "--backup",
        action="store_true",
        help="write a .bak copy beside each modified winebus.so",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="validate and print patch sites without writing",
    )
    args = parser.parse_args()

    targets = []
    for path in args.paths:
        targets.extend(winebus_targets(path))
    targets = sorted(set(targets))
    if not targets:
        print("ERROR: no winebus.so targets found", file=sys.stderr)
        return 1

    changed = 0
    try:
        for target in targets:
            changed += int(patch_one(target, backup=args.backup, dry_run=args.dry_run))
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    action = "would change" if args.dry_run else "changed"
    print(f"Done: {action} {changed} of {len(targets)} file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
