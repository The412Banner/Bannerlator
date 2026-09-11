#!/usr/bin/env python3
"""Start docs/releases/<version>.md from the previous stable release's notes.

    python3 scripts/new_release_notes.py <version> [--prev <tag>]

Copies the house layout from docs/releases/<prev>.md: the logo + badges header, the standing
LSFG guide callout, Carried over, Where to get Proton 9, the upstream credits list, the
trademark line and the footer, re-pointed at the new version. Leaves TODO(notes) placeholders
for the parts only a person can write (the lead paragraph and one section per change), and
lists every change merged since <prev> as a checklist to write from. release_notes.py refuses
to publish while any TODO(notes) remains.
"""
import argparse
import os
import re
import sys

sys.dont_write_bytecode = True  # no __pycache__ in the repo
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import release_notes as rn  # noqa: E402  (same folder)

TODO = rn.PLACEHOLDER


def sections(text):
    """Split on '# ' / '## ' headings: [(heading or '', body lines)]."""
    out, head, buf = [], "", []
    for line in text.split("\n"):
        if line.startswith("# ") or line.startswith("## "):
            out.append((head, buf))
            head, buf = line, []
        else:
            buf.append(line)
    out.append((head, buf))
    return out


def find(secs, prefix):
    for head, body in secs:
        if head.startswith(prefix):
            return head, "\n".join(body).strip("\n")
    return None, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("version")
    ap.add_argument("--prev")
    a = ap.parse_args()

    ver = a.version
    prev = a.prev or rn.previous_stable(ver)
    src = "docs/releases/%s.md" % prev
    dst = "docs/releases/%s.md" % ver
    if not os.path.isfile(src):
        rn.fail("%s not found; pass --prev with a release that has a notes file" % src)
    if os.path.exists(dst):
        rn.fail("%s already exists" % dst)
    old = open(src, encoding="utf-8").read()
    secs = sections(old)

    # Header: everything before '# Bannerlator <prev>', with the badge re-pointed.
    header = old.split("# Bannerlator %s" % prev, 1)[0]
    header = header.replace("badge/release-%s-" % prev, "badge/release-%s-" % ver)
    header = header.replace('alt="%s"' % prev, 'alt="%s"' % ver)

    guide = next((l for l in old.split("\n") if l.startswith("> 📘")), "")
    _, carried = find(secs, "## ⚠️ Carried over")
    _, proton = find(secs, "## 📦 Where to get Proton 9")
    proton = re.sub(r"Updating from [0-9.]+ changes nothing", "Updating from %s changes nothing" % prev, proton)
    _, credits = find(secs, "## 🙏 Credits")
    built_on = credits[credits.find("Built on the work"):] if "Built on the work" in credits else credits
    built_on = built_on.split("\n---")[0].rstrip()

    changes = "\n".join(rn.change_list(prev)) or "- (nothing merged since %s)" % prev

    doc = """{header}# Bannerlator {ver}

Run Windows apps and games on Android — no PC and no root required.

**{todo}: one bold paragraph — what this release is about, in plain language.**

<!-- update-summary: {todo}: one plain line for the in-app updater (no markdown). -->

> ⚠️ **{todo}: the biggest caveat of this release, or delete this line.**

{guide}

# What's New — everything since {prev}

<!-- {todo}: write one "## <emoji> <title>" section per user-facing change below, in plain language,
     each with a tested / untested callout (> ✅ / > ⚠️). Then delete this comment.
     The complete list is appended to the release page automatically.
{changes}
-->

## {todo}: first change

## 🔧 Smaller

- {todo}

## ⚠️ Carried over — still open

<!-- {todo}: check each item is still open; drop what this release fixed. Then delete this comment. -->
{carried}

## 📦 Where to get Proton 9

{proton}

---

## 🙏 Credits

**{todo}: thank this release's testers and reporters by name** (issue links + handles where they came from GitHub).

{built_on}

---

*Entirely app-side — no ImageFS reinstall. Install over {prev}; everything carries over.*
""".format(header=header, ver=ver, prev=prev, todo=TODO, guide=guide, changes=changes,
           carried=carried, proton=proton, built_on=built_on)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(doc)
    print("wrote %s from %s (%d TODO(notes) to fill)" % (dst, src, doc.count(TODO)))


if __name__ == "__main__":
    main()
