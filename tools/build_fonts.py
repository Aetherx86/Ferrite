#!/usr/bin/env python3
"""Cuts the app's typefaces down to the characters Korean transit text needs.

A subset is a modified font, and both source licenses allow that as long as the result does not keep a reserved
name, so the output is renamed. The copyright and license records inside each font are kept.

usage: build_fonts.py SANS_DIR MONO_DIR LINES_JSON OUT_DIR
"""
import json
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

SANS = {"Regular": "regular", "Medium": "medium", "Bold": "bold", "Black": "black"}
MONO = {"Medium": "medium", "Bold": "bold"}


def korean_set():
    chars = set()
    for hi in range(0xB0, 0xC9):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("euc_kr"))
            except UnicodeDecodeError:
                pass
    for code in range(0x3131, 0x3164):  # Standalone consonants and vowels, for initial sound search.
        chars.add(chr(code))
    return chars


def latin_set():
    chars = {chr(c) for c in range(0x20, 0x7F)}
    chars |= set("·•→←↑↓↗↘–—‘’“”…°×©℃㎞㎡")
    return chars


def rename(font, family, style):
    name = font["name"]
    full = f"{family} {style}"
    postscript = f"{family.replace(' ', '')}-{style}"
    keep = []
    for record in name.names:
        if record.nameID in (1, 16):
            record.string = family
        elif record.nameID in (2, 17):
            record.string = style
        elif record.nameID == 3:
            record.string = f"Aetherx86;{postscript}"
        elif record.nameID == 4:
            record.string = full
        elif record.nameID == 6:
            record.string = postscript
        elif record.nameID in (8, 9, 10, 11, 12, 18, 19, 21, 22, 25):
            continue
        keep.append(record)
    name.names = keep
    if "CFF " in font:
        top = font["CFF "].cff.topDictIndex[0]
        font["CFF "].cff.fontNames = [postscript]
        for attr, value in (("FullName", full), ("FamilyName", family), ("Weight", style)):
            if hasattr(top, attr):
                setattr(top, attr, value)


def cut(source, target, chars, family, style):
    font = TTFont(source)
    options = subset.Options()
    options.layout_features = ["kern", "liga", "calt", "tnum", "ccmp", "locl", "mark", "mkmk"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.hinting = False
    options.desubroutinize = False
    options.drop_tables += ["DSIG", "FFTM"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=[ord(c) for c in chars])
    subsetter.subset(font)
    rename(font, family, style)
    font.save(target)
    return len(font.getGlyphOrder())


def main(sans_dir, mono_dir, lines_json, out_dir):
    data = json.load(open(lines_json))
    chars = korean_set() | latin_set()
    for station in data["stations"]:
        chars |= set(station["name"]) | set(station.get("sub", "")) | set(station.get("en", ""))
    for weight, label in SANS.items():
        glyphs = cut(f"{sans_dir}/Pretendard-{weight}.otf", f"{out_dir}/ferrite_sans_{label}.otf", chars, "Ferrite Sans", weight)
        print("sans", weight, glyphs)
    mono_chars = latin_set()
    for weight, label in MONO.items():
        glyphs = cut(f"{mono_dir}/JetBrainsMono-{weight}.ttf", f"{out_dir}/ferrite_mono_{label}.ttf", mono_chars, "Ferrite Mono", weight)
        print("mono", weight, glyphs)


if __name__ == "__main__":
    main(*sys.argv[1:5])
