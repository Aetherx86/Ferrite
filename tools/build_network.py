#!/usr/bin/env python3
"""Puts the line graph, the schematic and the public statistics together into the files the app loads.

usage: build_network.py LINES_JSON LAYOUT_JSON TRANSFERS_CSV CONGESTION_CSV OUT_DIR
"""
import csv
import io
import json
import math
import re
import sys
from collections import defaultdict

# The loop at the far end of Line 6 only runs one way.
ONE_WAY = {"S6": [("응암", "역촌"), ("역촌", "불광"), ("불광", "독바위"), ("독바위", "연신내"), ("연신내", "구산"), ("구산", "응암")]}

# Faster trains need less time for the same distance.
SPEED = {"AR": 1.5, "GA": 3.0, "SN": 1.35, "GJ": 1.1, "GC": 1.15, "WS": 1.3, "GG": 1.2, "S1": 1.05, "DK": 1.5, "DH": 1.25}

LINE_BY_NAME = {f"{i}호선": f"S{i}" for i in range(1, 10)}
LINE_ALIASES = {
    "공항철도": "AR", "경의중앙선": "GJ", "경의선": "GJ", "중앙선": "GJ", "수인분당선": "SB", "분당선": "SB", "수인선": "SB", "신분당선": "SN",
    "경춘선": "GC", "우이신설선": "UI", "서해선": "WS", "김포골드라인": "GP", "경강선": "GG", "에버라인": "EV", "인천1호선": "I1", "인천2호선": "I2",
    "의정부경전철": "UJ", "신림선": "SL", "국철": "SB", "경원선": "S1", "경인선": "S1", "경부선": "S1", "안산선": "S4", "과천선": "S4", "일산선": "S3",
}


def metres(a, b):
    lat = math.radians((a["lat"] + b["lat"]) / 2)
    return math.hypot((a["lat"] - b["lat"]) * 110_950, (a["lon"] - b["lon"]) * 111_320 * math.cos(lat))


def read_csv(path):
    raw = open(path, "rb").read()
    for encoding in ("utf-8-sig", "cp949"):
        try:
            return list(csv.reader(io.StringIO(raw.decode(encoding))))
        except UnicodeDecodeError:
            pass
    raise ValueError(path)


def clean(name):
    name = re.sub(r"\(.*?\)", "", name).strip()
    return {"총신대입구": "이수", "서울": "서울역"}.get(name, name)


def main(lines_path, layout_path, transfers_path, congestion_path, out_dir):
    data = json.load(open(lines_path))
    layout = json.load(open(layout_path))
    stations = {s["id"]: s for s in data["stations"]}
    by_name = defaultdict(list)
    membership = defaultdict(set)
    for line in data["lines"]:
        for a, b in line["edges"]:
            membership[a].add(line["id"])
            membership[b].add(line["id"])
    for s in data["stations"]:
        by_name[s["name"]].append(s["id"])

    def find(name, line_id):
        for sid in by_name.get(clean(name), []):
            if line_id in membership[sid]:
                return sid
        return None

    out_stations = []
    used = set()
    for region, r in layout.items():
        for sid, position in r["positions"].items():
            sid = int(sid)
            s = stations[sid]
            used.add(sid)
            out_stations.append({
                "id": sid, "name": s["name"], "en": s["en"], "sub": s.get("sub", ""),
                "lat": s["lat"], "lon": s["lon"], "x": position[0], "y": position[1], "region": region,
            })
    out_stations.sort(key=lambda s: s["id"])

    out_lines = []
    for line in data["lines"]:
        if not line["edges"]:
            continue
        speed = SPEED.get(line["id"], 1.0)
        directed = []
        for a_name, b_name in ONE_WAY.get(line["id"], []):
            a, b = find(a_name, line["id"]), find(b_name, line["id"])
            directed.append([a, b])
        edges = []
        for a, b in line["edges"]:
            d = metres(stations[a], stations[b])
            edges.append([a, b, round(75 + d / (25 * speed))])
        out_lines.append({
            "id": line["id"], "name": line["name"], "short": line["short"], "color": line["color"],
            "region": line["region"], "cars": line["cars"], "live": line["live"], "edges": edges, "oneWay": directed,
        })

    # Transfer walks, as measured by the operator.
    transfers = []
    misses = []
    for row in read_csv(transfers_path)[1:]:
        if len(row) < 6:
            continue
        _, line_no, name, to, meters, clock = row[:6]
        line_id = LINE_BY_NAME.get(f"{line_no}호선")
        other = LINE_BY_NAME.get(to) or LINE_ALIASES.get(to)
        sid = find(name, line_id) if line_id else None
        if sid is None or other is None or other not in membership[sid]:
            misses.append((line_no, name, to))
            continue
        minutes, seconds = clock.split(":")
        transfers.append({"s": sid, "a": line_id, "b": other, "m": int(float(meters)), "sec": int(minutes) * 60 + int(seconds)})
    print("transfers", len(transfers), "unmatched", misses)

    runs = {region: [{"lines": c["lines"], "stations": c["stations"], "path": [v for p in c["path"] for v in p]} for c in r["chains"]] for region, r in layout.items()}
    regions = [{"id": k, "name": data["regions"][k], "w": layout[k]["width"], "h": layout[k]["height"]} for k in layout]
    network = {"regions": regions, "lines": out_lines, "stations": out_stations, "runs": runs, "transfers": transfers}
    json.dump(network, open(f"{out_dir}/network.json", "w"), ensure_ascii=False, separators=(",", ":"))

    # Average load by half hour, from the operator's published statistics.
    rows = read_csv(congestion_path)
    header = rows[0]
    slots = header[5:]
    days = {"평일": "weekday", "토요일": "saturday", "일요일": "sunday"}
    directions = {"상선": "U", "외선": "U", "하선": "D", "내선": "D"}
    table = {"slots": slots, "weekday": {}, "saturday": {}, "sunday": {}, "codes": {}}
    unmatched = 0
    for row in rows[1:]:
        day, line_name, code, name, direction = row[:5]
        line_id = LINE_BY_NAME.get(line_name)
        sid = find(name, line_id) if line_id else None
        if sid is None or direction not in directions or day not in days:
            unmatched += 1
            continue
        values = [round(float(v)) if v.strip() else 0 for v in row[5:]]
        table[days[day]][f"{line_id}|{sid}|{directions[direction]}"] = values
        table["codes"][f"{line_id}|{sid}"] = int(re.sub(r"\D", "", code) or 0)
    print("congestion rows", sum(len(table[d]) for d in days.values()), "unmatched", unmatched)
    json.dump(table, open(f"{out_dir}/congestion.json", "w"), ensure_ascii=False, separators=(",", ":"))
    print("stations", len(out_stations), "lines", len(out_lines))


if __name__ == "__main__":
    main(*sys.argv[1:6])
