#!/usr/bin/env python3
"""Builds the national base map: administrative borders and the real shape of the track.

usage: build_geo.py PROVINCES_TOPO MUNICIPALITIES_TOPO RAIL_EXTRACT LINES_JSON OUT_JSON
"""
import json
import math
import sys
from collections import defaultdict


def decode_topology(path):
    topo = json.load(open(path))
    transform = topo.get("transform")
    arcs = []
    for arc in topo["arcs"]:
        x = y = 0
        points = []
        for dx, dy in arc:
            x += dx
            y += dy
            if transform:
                points.append((x * transform["scale"][0] + transform["translate"][0], y * transform["scale"][1] + transform["translate"][1]))
            else:
                points.append((dx, dy))
        arcs.append(points)

    def ring(indices):
        out = []
        for index in indices:
            arc = arcs[index] if index >= 0 else list(reversed(arcs[~index]))
            out.extend(arc if not out else arc[1:])
        return out

    obj = next(iter(topo["objects"].values()))
    shapes = []
    for geometry in obj["geometries"]:
        rings = []
        if geometry["type"] == "Polygon":
            polygons = [geometry["arcs"]]
        elif geometry["type"] == "MultiPolygon":
            polygons = geometry["arcs"]
        else:
            continue
        for polygon in polygons:
            rings.append(ring(polygon[0]))  # Outer ring only; holes never show at this scale.
        shapes.append({"props": geometry.get("properties", {}), "rings": rings})
    return shapes


def simplify(points, tolerance):
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        lo, hi = stack.pop()
        ax, ay = points[lo]
        bx, by = points[hi]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        worst, where = 0.0, None
        for i in range(lo + 1, hi):
            px, py = points[i]
            distance = abs((px - ax) * dy - (py - ay) * dx) / length if length else math.hypot(px - ax, py - ay)
            if distance > worst:
                worst, where = distance, i
        if where is not None and worst > tolerance:
            keep[where] = True
            stack.append((lo, where))
            stack.append((where, hi))
    return [p for p, k in zip(points, keep) if k]


def encode(points, factor):
    """Flat integer list of first point then deltas, which keeps the file small."""
    out = []
    px = py = 0
    for lon, lat in points:
        x, y = round(lon * factor), round(lat * factor)
        out += [x - px, y - py]
        px, py = x, y
    return out


def shapes_out(shapes, tolerance, min_points, name_key="name"):
    out = []
    total = 0
    for shape in shapes:
        rings = []
        for ring in shape["rings"]:
            simple = simplify(ring, tolerance)
            if len(simple) >= min_points:
                rings.append(encode(simple, 10000))
                total += len(simple)
        if rings:
            out.append({"name": shape["props"].get(name_key, ""), "code": str(shape["props"].get("code", "")), "rings": rings})
    return out, total


def main(prov_path, muni_path, rail_path, lines_path, out_path):
    provinces, n1 = shapes_out(decode_topology(prov_path), 0.006, 4)
    municipalities, n2 = shapes_out(decode_topology(muni_path), 0.004, 4)
    print("provinces", len(provinces), n1, "municipalities", len(municipalities), n2)

    raw = json.load(open(rail_path))
    lines = json.load(open(lines_path))
    sys.path.insert(0, __file__.rsplit("/", 1)[0])
    from extract_lines import LINES, EXPRESS

    ways = {e["id"]: e for e in raw["elements"] if e["type"] == "way" and "geometry" in e}
    relations = [e for e in raw["elements"] if e["type"] == "relation"]
    per_line = defaultdict(set)
    other = set()
    for rel in relations:
        tags = rel["tags"]
        name = tags.get("name", "")
        if "광주 도시철도 2호선" in name or "자기부상" in name or "월미" in name:
            continue
        way_ids = {m["ref"] for m in rel["members"] if m["type"] == "way" and m["role"] in ("", "forward", "backward")}
        matched = False
        for line in LINES:
            for network, ref in line[7]:
                if network == "코레일":
                    hit = tags.get("network") == network and name.startswith(ref)
                else:
                    hit = tags.get("ref") == ref and tags.get("network", "-") == network
                if hit:
                    per_line[line[0]] |= way_ids
                    matched = True
        if not matched and tags.get("route") == "train":
            other |= way_ids

    def join(way_ids, tolerance):
        """Joins ways end to end into long runs before simplifying, so joints do not show."""
        runs = []
        ends = defaultdict(list)
        pieces = []
        for wid in way_ids:
            way = ways.get(wid)
            if way:
                pieces.append([(p["lon"], p["lat"]) for p in way["geometry"]])
        used = [False] * len(pieces)
        for i, piece in enumerate(pieces):
            ends[piece[0]].append(i)
            ends[piece[-1]].append(i)
        for i, piece in enumerate(pieces):
            if used[i]:
                continue
            used[i] = True
            run = list(piece)
            grew = True
            while grew:
                grew = False
                for j in ends[run[-1]]:
                    if not used[j]:
                        used[j] = True
                        nxt = pieces[j]
                        run += nxt[1:] if nxt[0] == run[-1] else list(reversed(nxt))[1:]
                        grew = True
                        break
                for j in ends[run[0]]:
                    if not used[j]:
                        used[j] = True
                        nxt = pieces[j]
                        run = (nxt if nxt[-1] == run[0] else list(reversed(nxt)))[:-1] + run
                        grew = True
                        break
            runs.append(run)
        out = []
        total = 0
        for run in runs:
            simple = simplify(run, tolerance)
            if len(simple) >= 2:
                out.append(encode(simple, 100000))
                total += len(simple)
        return out, total

    tracks = {}
    total_points = 0
    for line_id, way_ids in per_line.items():
        runs, n = join(way_ids, 0.00018)
        tracks[line_id] = runs
        total_points += n
    rail, n3 = join(other - set().union(*per_line.values()), 0.0006)
    print("track points", total_points, "intercity points", n3)
    json.dump({"provinces": provinces, "municipalities": municipalities, "tracks": tracks, "rail": rail}, open(out_path, "w"), ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(*sys.argv[1:6])
