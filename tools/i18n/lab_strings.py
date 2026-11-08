#!/usr/bin/env python3
"""Lab's translatable text (see ui/simple/LabText.kt).

Lab's text is English in the code, inside L("..."). Each English string gets a resource named
"lab_" + its CRC-32 (8 hex digits), the same name LabLang.keyOf() computes at run time.

  lab_strings.py extract          write app/src/main/res/values/strings_lab.xml from every L("...") in the code
  lab_strings.py check            the same, but only report (exit 1 when the file is out of date or a call is bad)
  lab_strings.py fill LANG FILE   write values-LANG/strings_lab.xml from FILE, a JSON object {english: translation}
  lab_strings.py missing LANG     print the English strings values-LANG/strings_lab.xml does not have yet (JSON list)

A translation must keep the English's %1$s / %2$d placeholders; fill refuses one that does not.
"""
import json
import os
import re
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java")
RES = os.path.join(ROOT, "app", "src", "main", "res")
HEADER_EN = "<!-- Lab's text in English. Written by tools/i18n/lab_strings.py from the L(\"...\") calls; do not edit by hand. -->"
HEADER_TR = ("<!-- Lab's text in {lang}. {source} Corrections welcome: change the text, keep the name and any %1$s / %2$d. "
             "Written by tools/i18n/lab_strings.py. -->")

CALL = re.compile(r'(?<![A-Za-z0-9_.])[LT]\(\s*"')
PLACEHOLDER = re.compile(r"%(\d+)\$[sdf]")


def key_of(english):
    return "lab_%08x" % (zlib.crc32(english.encode("utf-8")) & 0xFFFFFFFF)


def read_literal(text, i):
    """Reads the Kotlin string literal whose opening quote is at text[i]. Returns (value, end index, error)."""
    assert text[i] == '"'
    j = i + 1
    out = []
    while j < len(text):
        c = text[j]
        if c == '"':
            return "".join(out), j + 1, None
        if c == "\n":
            return None, j, "line break inside the literal"
        if c == "\\":
            n = text[j + 1]
            if n == "u":
                out.append(chr(int(text[j + 2:j + 6], 16)))
                j += 6
                continue
            out.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'", "$": "$"}.get(n, n))
            j += 2
            continue
        if c == "$" and j + 1 < len(text) and (text[j + 1] == "{" or text[j + 1].isalpha() or text[j + 1] == "_"):
            return None, j, "a $template inside L(): use %1$s and pass the value as an argument"
        out.append(c)
        j += 1
    return None, j, "unterminated literal"


def scan():
    """Every L("...") English string in the code, with where it is."""
    found = {}
    errors = []
    for base, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(base, f)
            text = open(path, encoding="utf-8").read()
            for m in CALL.finditer(text):
                q = m.end() - 1
                value, end, err = read_literal(text, q)
                line = text.count("\n", 0, q) + 1
                where = "%s:%d" % (os.path.relpath(path, ROOT), line)
                if err:
                    errors.append("%s: %s" % (where, err))
                    continue
                # Only the first argument is the English; a + right after it would make it a different string at run time.
                rest = text[end:end + 40].lstrip()
                if rest.startswith("+"):
                    errors.append("%s: L(\"...\" + ...) — the English must be one literal" % where)
                    continue
                found.setdefault(value, []).append(where)
    return found, errors


def xml_escape(s):
    s = s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace('"', '\\"').replace("'", "\\'").replace("\n", "\\n").replace("\t", "\\t")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def string_line(name, value):
    attrs = ' name="%s"' % name
    # A % that is not a %1$s placeholder: tell aapt it is plain text.
    if "%" in value and not PLACEHOLDER.search(value):
        attrs += ' formatted="false"'
    return "    <string%s>%s</string>" % (attrs, xml_escape(value))


def write(path, header, items):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', header, "<resources>"]
    for name, value in sorted(items, key=lambda x: x[0]):
        lines.append(string_line(name, value))
    lines.append("</resources>")
    data = "\n".join(lines) + "\n"
    old = open(path, encoding="utf-8").read() if os.path.exists(path) else None
    if old != data:
        open(path, "w", encoding="utf-8").write(data)
    return old != data


def english_items(found):
    names = {}
    for s in found:
        k = key_of(s)
        if k in names and names[k] != s:
            raise SystemExit("CRC-32 clash: %r and %r" % (names[k], s))
        names[k] = s
    return [(key_of(s), s) for s in found]


def lang_dir(lang):
    # "es-419" → values-b+es+419; "pt-PT" → values-pt-rPT; "zh-CN" → values-zh-rCN; "in" → values-in.
    if "-" not in lang:
        return "values-" + lang
    a, b = lang.split("-", 1)
    return "values-b+%s+%s" % (a, b) if b.isdigit() else "values-%s-r%s" % (a, b)


def read_translations(lang):
    path = os.path.join(RES, lang_dir(lang), "strings_lab.xml")
    if not os.path.exists(path):
        return {}
    out = {}
    for m in re.finditer(r'<string name="(lab_[0-9a-f]{8})"[^>]*>(.*?)</string>', open(path, encoding="utf-8").read(), re.S):
        out[m.group(1)] = m.group(2)
    return out


def unescape_xml(s):
    s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    out, i = [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            out.append({"n": "\n", "t": "\t"}.get(s[i + 1], s[i + 1]))
            i += 2
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    found, errors = scan()
    for e in errors:
        print("bad L() call:", e, file=sys.stderr)
    en_path = os.path.join(RES, "values", "strings_lab.xml")
    if cmd in ("extract", "check"):
        items = english_items(found)
        if cmd == "extract":
            changed = write(en_path, HEADER_EN, items)
            print("%d strings, %s" % (len(items), "written" if changed else "unchanged"))
        else:
            want = open(en_path, encoding="utf-8").read() if os.path.exists(en_path) else ""
            tmp = en_path + ".check"
            write(tmp, HEADER_EN, items)
            same = open(tmp, encoding="utf-8").read() == want
            os.remove(tmp)
            print("%d strings, strings_lab.xml %s" % (len(items), "up to date" if same else "OUT OF DATE (run extract)"))
            if not same:
                errors.append("out of date")
        sys.exit(1 if errors else 0)
    if cmd == "missing":
        lang = sys.argv[2]
        have = read_translations(lang)
        print(json.dumps(sorted(s for s in found if key_of(s) not in have), ensure_ascii=False, indent=1))
        return
    if cmd == "fill":
        lang, src = sys.argv[2], sys.argv[3]
        source = sys.argv[4] if len(sys.argv) > 4 else "Machine-drafted translation."
        new = json.load(open(src, encoding="utf-8"))
        have = {k: unescape_xml(v) for k, v in read_translations(lang).items()}
        bad = 0
        for en, tr in new.items():
            if en not in found:
                continue
            if not isinstance(tr, str) or not tr.strip():
                bad += 1
                continue
            if sorted(PLACEHOLDER.findall(en)) != sorted(PLACEHOLDER.findall(tr)):
                print("placeholders differ, skipped: %r -> %r" % (en, tr), file=sys.stderr)
                bad += 1
                continue
            have[key_of(en)] = tr
        live = {key_of(s) for s in found}
        items = [(k, v) for k, v in have.items() if k in live]
        write(os.path.join(RES, lang_dir(lang), "strings_lab.xml"), HEADER_TR.format(lang=lang, source=source), items)
        print("%s: %d of %d translated (%d skipped)" % (lang, len(items), len(found), bad))
        return
    raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
