#!/usr/bin/env python3
"""Turns a raw rail extract into one clean station graph per line.

Input is the JSON answer to tools/rail_query.overpassql. Output is lines.json, which holds stations with
coordinates, and for each line the adjacency between them, with express services and unopened routes
removed. Nothing here is hand-typed except the table of lines below and the corrections after it.
"""
import json
import math
import re
import sys
from collections import defaultdict

# id, name, short label, colour, region, cars, live id, (network substring, ref) matchers
LINES = [
    ("S1", "1호선", "1", "#0052A4", "capital", 10, 1001, [("수도권 전철", "1")]),
    ("S2", "2호선", "2", "#00A84D", "capital", 10, 1002, [("수도권 전철", "2")]),
    ("S3", "3호선", "3", "#EF7C1C", "capital", 10, 1003, [("수도권 전철", "3")]),
    ("S4", "4호선", "4", "#00A5DE", "capital", 10, 1004, [("수도권 전철", "4")]),
    ("S5", "5호선", "5", "#996CAC", "capital", 8, 1005, [("수도권 전철", "5")]),
    ("S6", "6호선", "6", "#CD7C2F", "capital", 8, 1006, [("수도권 전철", "6")]),
    ("S7", "7호선", "7", "#747F00", "capital", 8, 1007, [("수도권 전철", "7")]),
    ("S8", "8호선", "8", "#E6186C", "capital", 6, 1008, [("수도권 전철", "8")]),
    ("S9", "9호선", "9", "#BDB092", "capital", 6, 1009, [("수도권 전철", "9")]),
    ("GJ", "경의중앙선", "경의", "#77C4A3", "capital", 8, 1063, [("수도권 전철", "경의·중앙")]),
    ("SB", "수인분당선", "수인", "#F5A200", "capital", 6, 1075, [("수도권 전철", "수인·분당")]),
    ("SN", "신분당선", "신분", "#D4003B", "capital", 6, 1077, [("수도권 전철", "신분당")]),
    ("GC", "경춘선", "경춘", "#0C8E72", "capital", 8, 1067, [("수도권 전철", "경춘")]),
    ("AR", "공항철도", "공항", "#0090D2", "capital", 6, 1065, [("수도권 전철", "공항철도")]),
    ("WS", "서해선", "서해", "#81A914", "capital", 4, 1093, [("수도권 전철", "서해")]),
    ("GG", "경강선", "경강", "#003DA5", "capital", 4, 1081, [("수도권 전철", "경강")]),
    ("GA", "GTX-A", "GTX", "#9A6292", "capital", 8, 1032, [("수도권 전철", "GTX-A")]),
    ("I1", "인천1호선", "인1", "#7CA8D5", "capital", 8, None, [("수도권 전철", "인천1")]),
    ("I2", "인천2호선", "인2", "#ED8B00", "capital", 2, None, [("수도권 전철;인천도시철도", "I2")]),
    ("UI", "우이신설선", "우이", "#B0CE18", "capital", 2, 1092, [("-", "W")]),
    ("SL", "신림선", "신림", "#6789CA", "capital", 3, 1094, [("수도권 전철", "Silim")]),
    ("GP", "김포골드라인", "김포", "#A17800", "capital", 2, None, [("수도권 전철", "김포 골드라인")]),
    ("EV", "용인에버라인", "용인", "#6FB245", "capital", 1, None, [("수도권 전철", "용인")]),
    ("UJ", "의정부경전철", "의정", "#FDA600", "capital", 2, None, [("수도권 전철", "U")]),
    ("GO", "교외선", "교외", "#E06040", "capital", 4, None, [("코레일", "교외선")]),
    ("B1", "부산 1호선", "1", "#F06A00", "busan", 8, None, [("부산 도시철도", "1")]),
    ("B2", "부산 2호선", "2", "#81BF48", "busan", 6, None, [("부산 도시철도", "2")]),
    ("B3", "부산 3호선", "3", "#BB8C00", "busan", 6, None, [("부산 도시철도", "3")]),
    ("B4", "부산 4호선", "4", "#217DCB", "busan", 6, None, [("부산 도시철도", "4")]),
    ("BG", "부산김해경전철", "김해", "#8652A1", "busan", 2, None, [("부산 도시철도", "BGL")]),
    ("DH", "동해선", "동해", "#0054A6", "busan", 4, None, [("부산 도시철도", "동해")]),
    ("D1", "대구 1호선", "1", "#D93F5C", "daegu", 6, None, [("대구 도시철도", "1")]),
    ("D2", "대구 2호선", "2", "#00AA80", "daegu", 6, None, [("대구 도시철도", "2")]),
    ("D3", "대구 3호선", "3", "#FFB100", "daegu", 3, None, [("대구도시철도", "3")]),
    ("DK", "대경선", "대경", "#0054A6", "daegu", 4, None, [("-", "대경")]),
    ("DJ", "대전 1호선", "1", "#007448", "daejeon", 4, None, [("대전 도시철도", "1")]),
    ("GW", "광주 1호선", "1", "#009088", "gwangju", 4, None, [("광주 도시철도", "1")]),
]

REGIONS = {"capital": "수도권", "busan": "부산·울산", "daegu": "대구·경북", "daejeon": "대전", "gwangju": "광주"}

EXPRESS = re.compile(r"(?<!광역)급행|특급|직통|ITX|셔틀|임시|시운전")

# Same place spelled differently between services, reduced to one name.
RENAMES = {
    "서울": "서울역",
    "총신대입구": "이수",
    "총신대입구(이수)": "이수",
    "이수(총신대입구)": "이수",
    "인천공항1터미널": "인천공항1터미널",
}

# Stations that share a name and sit close together, but are separate places to change between.
SEPARATE = {"신촌", "양평"}

CLUSTER_RADIUS_M = 1300

# Stops the source leaves unnamed, or names like the stop next to them, by node.
NODE_NAMES = {
    9459739653: "보문",
    5919544693: "디지털미디어시티",
    5893030245: "가좌",
    9459739637: "삼양사거리",
}

# Stops the source lists out of order, or services it only records in part.
EDGE_FIXES = {
    "GJ": {"remove": [("원덕", "지평")], "add": [("용문", "지평")]},
    "DK": {"remove": [("경산", "북삼"), ("구미", "북삼"), ("사곡", "왜관")], "add": [("사곡", "북삼"), ("북삼", "왜관")]},
}


def metres(a, b):
    lat = math.radians((a[0] + b[0]) / 2)
    return math.hypot((a[0] - b[0]) * 110_950, (a[1] - b[1]) * 111_320 * math.cos(lat))


def clean_name(raw):
    name = re.sub(r"\s+", "", raw.strip())
    name = re.sub(r"\(.*?\)", "", name)
    name = re.sub(r"(역|승강장)$", "", name) if name not in ("서울역",) and len(name) > 2 else name
    return RENAMES.get(name, name)


def sub_name(raw):
    found = re.search(r"\((.*?)\)", raw)
    return found.group(1).strip() if found else ""


CHO = "g kk n d tt r m b pp s ss _ j jj ch k t p h".split()
JUNG = "a ae ya yae eo e yeo ye o wa wae oe yo u wo we wi yu eu ui i".split()
JONG = ["", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"]


def romanize(name):
    """Revised Romanization with the sound changes that matter in place names."""
    syllables = []
    for ch in name:
        code = ord(ch) - 0xAC00
        if 0 <= code < 11172:
            syllables.append([code // 588, (code % 588) // 28, code % 28])
        else:
            syllables.append(ch)
    out = []
    for i, syllable in enumerate(syllables):
        if isinstance(syllable, str):
            out.append(syllable)
            continue
        cho, jung, jong = syllable
        next_cho = syllables[i + 1][0] if i + 1 < len(syllables) and not isinstance(syllables[i + 1], str) else None
        prev = syllables[i - 1] if i > 0 and not isinstance(syllables[i - 1], str) else None
        first = CHO[cho].replace("_", "")
        if prev is not None:
            prev_coda = JONG[prev[2]]
            if cho == 5:  # ㄹ after a consonant.
                first = "l" if prev_coda == "l" else "n"
            elif cho == 2 and prev_coda == "l":
                first = "l"
            elif cho == 11 and prev_coda:  # A coda carries over into an empty onset.
                carried = {"k": "g", "t": "d", "p": "b", "l": "r", "n": "n", "m": "m"}.get(prev_coda)
                first = carried or ""
        coda = JONG[jong]
        if next_cho == 11 and coda in ("k", "t", "p", "l", "n", "m"):
            coda = ""
        elif next_cho in (2, 6) and coda == "k":
            coda = "ng"
        elif next_cho in (2, 6) and coda == "p":
            coda = "m"
        elif next_cho in (2, 6) and coda == "t":
            coda = "n"
        elif next_cho == 5 and coda == "n":
            coda = "l"
        elif next_cho == 5 and coda in ("ng", "m"):
            pass
        elif coda == "l" and next_cho == 5:
            pass
        out.append(first + JUNG[jung] + coda)
    text = "".join(out)
    return text[:1].upper() + text[1:]


def clean_english(raw):
    text = re.sub(r"\s*\(.*?\)", "", raw or "").strip()
    text = re.sub(r"\s+(Station|station|Stn\.?)$", "", text)
    return text


def main(raw_path, out_path):
    raw = json.load(open(raw_path))
    nodes = {e["id"]: e for e in raw["elements"] if e["type"] == "node"}
    relations = [e for e in raw["elements"] if e["type"] == "relation"]

    per_line = defaultdict(list)
    used = {}
    for rel in relations:
        tags = rel["tags"]
        if tags.get("type") != "route":
            continue
        name = tags.get("name", "")
        if EXPRESS.search(name) and not name.startswith("교외선"):
            continue
        if "광주 도시철도 2호선" in name:
            continue  # Not open yet.
        for line in LINES:
            for network, ref in line[7]:
                if network == "코레일":
                    if tags.get("network") == network and name.startswith(ref):
                        per_line[line[0]].append(rel)
                elif tags.get("ref") == ref and tags.get("network", "-") == network:
                    per_line[line[0]].append(rel)

    # Every stop on every service, by line.
    sightings = []
    sequences = defaultdict(list)
    for line_id, rels in per_line.items():
        for rel in rels:
            seq = []
            for member in rel["members"]:
                if member["type"] != "node" or not member["role"].startswith("stop"):
                    continue
                node = nodes.get(member["ref"])
                if not node:
                    continue
                tags = node.get("tags", {})
                raw_name = NODE_NAMES.get(member["ref"]) or tags.get("name:ko") or tags.get("name")
                if not raw_name:
                    continue
                entry = {
                    "name": clean_name(raw_name),
                    "sub": sub_name(raw_name),
                    "en": clean_english(tags.get("name:en") or tags.get("int_name") or tags.get("name:ko-Latn")),
                    "lat": node["lat"],
                    "lon": node["lon"],
                    "line": line_id,
                }
                sightings.append(entry)
                seq.append(entry)
            if len(seq) >= 2:
                sequences[line_id].append((rel["tags"].get("name", ""), seq))

    # One station per name and place.
    clusters = []
    by_name = defaultdict(list)
    for entry in sightings:
        by_name[entry["name"]].append(entry)
    assignment = {}
    for name, entries in by_name.items():
        local = []
        for entry in entries:
            key = (entry["line"] if name in SEPARATE else None)
            for cluster in local:
                if cluster["key"] == key and metres((cluster["lat"], cluster["lon"]), (entry["lat"], entry["lon"])) < CLUSTER_RADIUS_M:
                    cluster["members"].append(entry)
                    n = len(cluster["members"])
                    cluster["lat"] += (entry["lat"] - cluster["lat"]) / n
                    cluster["lon"] += (entry["lon"] - cluster["lon"]) / n
                    assignment[id(entry)] = cluster
                    break
            else:
                cluster = {"key": key, "name": name, "lat": entry["lat"], "lon": entry["lon"], "members": [entry]}
                local.append(cluster)
                assignment[id(entry)] = cluster
        clusters.extend(local)
    clusters.sort(key=lambda c: (c["name"], c["lat"]))
    for index, cluster in enumerate(clusters):
        cluster["id"] = index

    out_stations = []
    for cluster in clusters:
        ens = [m["en"] for m in cluster["members"] if m["en"]]
        en = max(set(ens), key=ens.count) if ens else romanize(cluster["name"])
        subs = [m["sub"] for m in cluster["members"] if m["sub"]]
        out_stations.append({
            "id": cluster["id"], "name": cluster["name"], "en": en, "sub": subs[0] if subs else "",
            "lat": round(cluster["lat"], 6), "lon": round(cluster["lon"], 6),
        })

    by_id = {c["id"]: c for c in clusters}

    def distance(a, b):
        return metres((by_id[a]["lat"], by_id[a]["lon"]), (by_id[b]["lat"], by_id[b]["lon"]))

    def has_detour(edge_set, a, b):
        """True if a..b also runs through other stations of this line, along about the same way."""
        adjacency = defaultdict(set)
        for u, v in edge_set:
            if (u, v) != (min(a, b), max(a, b)):
                adjacency[u].add(v)
                adjacency[v].add(u)
        limit = distance(a, b) * 1.45 + 400
        stack = [(a, 0.0, 0)]
        best = {}
        while stack:
            node, travelled, hops = stack.pop()
            if node == b:
                return True
            if hops >= 12:
                continue
            for other in adjacency[node]:
                total = travelled + distance(node, other)
                if total <= limit and best.get(other, 1e18) > total:
                    best[other] = total
                    stack.append((other, total, hops + 1))
        return False

    def ids_named(names, line_ids):
        for name in names:
            yield next(c["id"] for c in clusters if c["name"] == name and line_ids & {m["line"] for m in c["members"]})

    out_lines = []
    for line in LINES:
        line_id = line[0]
        edges = defaultdict(int)
        for _, seq in sequences[line_id]:
            ids = [assignment[id(e)]["id"] for e in seq]
            for a, b in zip(ids, ids[1:]):
                if a != b:
                    edges[(min(a, b), max(a, b))] += 1
        # Services that skip stops leave edges that jump over stations. They go, longest first.
        kept = set(edges)
        for a, b in sorted(edges, key=lambda e: -distance(*e)):
            if has_detour(kept, a, b):
                kept.discard((a, b))
        fixes = EDGE_FIXES.get(line_id, {})
        for names in fixes.get("remove", []):
            a, b = ids_named(names, {line_id})
            kept.discard((min(a, b), max(a, b)))
        for names in fixes.get("add", []):
            a, b = ids_named(names, {line_id})
            kept.add((min(a, b), max(a, b)))
        edges = dict.fromkeys(kept, 1)
        out_lines.append({
            "id": line_id, "name": line[1], "short": line[2], "color": line[3], "region": line[4],
            "cars": line[5], "live": line[6],
            "edges": sorted(edges.keys()),
        })

    json.dump({"regions": REGIONS, "lines": out_lines, "stations": out_stations}, open(out_path, "w"), ensure_ascii=False, indent=0)

    names = {s["id"]: s["name"] for s in out_stations}
    for line in out_lines:
        deg = defaultdict(list)
        for a, b in line["edges"]:
            deg[a].append(b)
            deg[b].append(a)
        leaves = [names[k] for k, v in deg.items() if len(v) == 1]
        forks = [f"{names[k]}({len(v)})" for k, v in deg.items() if len(v) > 2]
        print(f"{line['id']:3} {line['name']:8} stations={len(deg):3} edges={len(line['edges']):3} rels={len(per_line[line['id']]):3} leaves={leaves} forks={forks}")
    print("stations", len(out_stations))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
