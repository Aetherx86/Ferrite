#!/usr/bin/env python3
"""Draws the network as a schematic: lines only run horizontally, vertically or at 45 degrees.

No map was traced for this. Stations start where they really are, spacing is then evened out so the dense
center gets room and the far suburbs do not stretch the map, and every run of track between two interchanges
is routed across a grid with bends priced in. A final local search moves interchanges while that lowers the
total cost. The same code lays out every city.
"""
import heapq
import json
import math
import os
import random
import sys
import time
from collections import defaultdict

import numpy as np

DIRS = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
SQRT2 = math.sqrt(2)
BEND_COST = {0: 0.0, 1: 2.5, 2: 7.0}
CROSS_COST = 9.0
DIAGONAL_FACTOR = 1.04
SHORT_PENALTY = 9.0

# Per region: grid cells per station hop, radial compression distance in km, and search time in seconds.
REGION_SETTINGS = {
    "capital": {
        "cells": 2.2, "compress_km": 16.0, "search": 300, "center": (37.545, 126.99),
        "hubs": [((37.46, 126.70), 0.7, 7.0), ((37.27, 127.03), 0.45, 6.0), ((37.40, 127.11), 0.45, 6.0), ((37.68, 126.78), 0.45, 6.0)],
    },
    "busan": {"cells": 2.2, "compress_km": 14.0, "search": 25, "center": (35.17, 129.07)},
    "daegu": {"cells": 2.2, "compress_km": 14.0, "search": 15, "center": (35.87, 128.60)},
    "daejeon": {"cells": 2.2, "compress_km": 14.0, "search": 3, "center": (36.35, 127.38)},
    "gwangju": {"cells": 2.2, "compress_km": 14.0, "search": 3, "center": (35.15, 126.85)},
}


def turn(d1, d2):
    diff = abs(d1 - d2) % 8
    return min(diff, 8 - diff)


class Layout:
    def __init__(self, nodes, chains, cells, seed=7):
        self.nodes = nodes  # key node id -> ideal (x, y) in cells
        self.chains = chains
        self.pos = {}
        self.cell_node = {}
        self.occupied = defaultdict(dict)  # cell -> chain index -> direction through it
        self.diagonals = defaultdict(set)
        self.ports = defaultdict(dict)  # node -> port direction -> chain index
        self.routes = {}
        self.costs = {}
        self.incident = defaultdict(list)
        for index, chain in enumerate(chains):
            self.incident[chain["a"]].append(index)
            self.incident[chain["b"]].append(index)
        self.rng = random.Random(seed)

    def snap(self):
        order = sorted(self.nodes, key=lambda n: -len(self.incident[n]))
        for node in order:
            x, y = self.nodes[node]
            best = None
            for radius in range(0, 40):
                candidates = []
                for dx in range(-radius, radius + 1):
                    for dy in range(-radius, radius + 1):
                        if max(abs(dx), abs(dy)) != radius:
                            continue
                        cell = (round(x) + dx, round(y) + dy)
                        if self.is_free_for_node(cell):
                            candidates.append((math.hypot(cell[0] - x, cell[1] - y), cell))
                if candidates:
                    best = min(candidates)[1]
                    break
            self.place(node, best)

    def is_free_for_node(self, cell):
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (cell[0] + dx, cell[1] + dy) in self.cell_node:
                    return False
        return True

    def place(self, node, cell):
        self.pos[node] = cell
        self.cell_node[cell] = node

    def unplace(self, node):
        del self.cell_node[self.pos[node]]
        del self.pos[node]

    def route(self, index, widen=1.0):
        chain = self.chains[index]
        start, goal = self.pos[chain["a"]], self.pos[chain["b"]]
        if start == goal:
            return None
        need = chain["hops"] * 1.7
        margin = int(widen * max(6, 0.45 * max(abs(start[0] - goal[0]), abs(start[1] - goal[1]), need)))
        lo_x, hi_x = min(start[0], goal[0]) - margin, max(start[0], goal[0]) + margin
        lo_y, hi_y = min(start[1], goal[1]) - margin, max(start[1], goal[1]) + margin
        used_start = self.ports[chain["a"]]
        used_goal = self.ports[chain["b"]]
        crowded_start = len(self.incident[chain["a"]]) > 8
        crowded_goal = len(self.incident[chain["b"]]) > 8

        def near(cell, node_cell):
            return max(abs(cell[0] - node_cell[0]), abs(cell[1] - node_cell[1])) == 1

        def heuristic(x, y):
            dx, dy = abs(x - goal[0]), abs(y - goal[1])
            return max(dx, dy) + (SQRT2 - 1) * min(dx, dy)

        frontier = [(heuristic(*start), 0.0, start[0], start[1], -1)]
        best = {(start[0], start[1], -1): 0.0}
        parent = {}
        final = None
        while frontier:
            _, cost, x, y, d = heapq.heappop(frontier)
            if best.get((x, y, d), 1e18) < cost:
                continue
            if (x, y) == goal:
                final = (x, y, d, cost)
                break
            crossing_here = d != -1 and (x, y) in self.occupied and (x, y) != start
            for nd, (dx, dy) in enumerate(DIRS):
                if d == -1:
                    if nd in used_start and not crowded_start:
                        continue
                    bend = 0.0
                else:
                    t = turn(d, nd)
                    if t > 2:
                        continue
                    if crossing_here and t != 0:
                        continue
                    bend = BEND_COST[t]
                nx, ny = x + dx, y + dy
                if not (lo_x <= nx <= hi_x and lo_y <= ny <= hi_y):
                    continue
                cell = (nx, ny)
                extra = 0.0
                if cell == goal:
                    if (nd + 4) % 8 in used_goal and not crowded_goal:
                        continue
                else:
                    if cell in self.cell_node:
                        continue
                    through = self.occupied.get(cell)
                    if through and ((crowded_start and near(cell, start)) or (crowded_goal and near(cell, goal))):
                        extra += 2 * CROSS_COST
                    elif through:
                        if len(through) > 1:
                            continue
                        other = next(iter(through.values()))
                        if other is None or turn(other, nd) == 0 or turn(other, nd) == 4:
                            continue  # Only straight across a run that itself goes straight here.
                        extra += CROSS_COST
                if dx and dy:
                    corner = (min(x, nx), min(y, ny))
                    slope = 1 if dx == dy else -1
                    if (corner, -slope) in self.diagonals:
                        extra += CROSS_COST
                    if (corner, slope) in self.diagonals:
                        continue
                step = (SQRT2 * DIAGONAL_FACTOR if dx and dy else 1.0) + bend + extra
                total = cost + step
                key = (nx, ny, nd)
                if total < best.get(key, 1e18):
                    best[key] = total
                    parent[key] = (x, y, d)
                    heapq.heappush(frontier, (total + heuristic(nx, ny), total, nx, ny, nd))
        if final is None:
            return None
        x, y, d, cost = final
        cells = []
        dirs = []
        key = (x, y, d)
        while key in parent:
            cells.append((key[0], key[1]))
            dirs.append(key[2])
            key = parent[key]
        cells.append(start)
        cells.reverse()
        dirs.reverse()
        length = sum(SQRT2 if DIRS[n][0] and DIRS[n][1] else 1.0 for n in dirs)
        cost += SHORT_PENALTY * max(0.0, need - length)
        # Keep the map close to where things really are.
        return {"cells": cells, "dirs": dirs, "cost": cost}

    def commit(self, index, route):
        chain = self.chains[index]
        cells, dirs = route["cells"], route["dirs"]
        self.routes[index] = route
        self.costs[index] = route["cost"]
        self.ports[chain["a"]].setdefault(dirs[0], index)
        self.ports[chain["b"]].setdefault((dirs[-1] + 4) % 8, index)
        for position in range(1, len(cells) - 1):
            straight = dirs[position - 1] if dirs[position - 1] == dirs[position] else None
            self.occupied[cells[position]][index] = straight
        for position, d in enumerate(dirs):
            dx, dy = DIRS[d]
            if dx and dy:
                x, y = cells[position]
                corner = (min(x, x + dx), min(y, y + dy))
                slope = 1 if dx == dy else -1
                self.diagonals[(corner, slope)].add(index)

    def uncommit(self, index):
        route = self.routes.pop(index)
        chain = self.chains[index]
        cells, dirs = route["cells"], route["dirs"]
        del self.costs[index]
        for node, port in ((chain["a"], dirs[0]), (chain["b"], (dirs[-1] + 4) % 8)):
            if self.ports[node].get(port) == index:
                del self.ports[node][port]
        for position in range(1, len(cells) - 1):
            through = self.occupied.get(cells[position])
            if through is None:
                continue
            through.pop(index, None)
            if not through:
                del self.occupied[cells[position]]
        for position, d in enumerate(dirs):
            dx, dy = DIRS[d]
            if dx and dy:
                x, y = cells[position]
                corner = (min(x, x + dx), min(y, y + dy))
                slope = 1 if dx == dy else -1
                self.diagonals[(corner, slope)].discard(index)
                if not self.diagonals[(corner, slope)]:
                    del self.diagonals[(corner, slope)]

    def position_cost(self, node, cell):
        ideal = self.nodes[node]
        return 0.35 * math.hypot(cell[0] - ideal[0], cell[1] - ideal[1])

    def route_all(self, order, widen=1.0):
        failed = []
        for index in order:
            route = self.route(index, widen)
            if route is None:
                failed.append(index)
            else:
                self.commit(index, route)
        return failed

    def total(self):
        return sum(self.costs.values()) + sum(self.position_cost(n, c) for n, c in self.pos.items())

    def improve(self, seconds):
        deadline = time.time() + seconds
        nodes = list(self.nodes)
        passes = 0
        while time.time() < deadline:
            self.rng.shuffle(nodes)
            improved = 0
            for node in nodes:
                if time.time() >= deadline:
                    break
                improved += self.try_moves(node)
            passes += 1
            if not improved:
                break
        return passes

    def try_moves(self, node):
        chains = self.incident[node]
        cell = self.pos[node]
        before = sum(self.costs.get(c, 0) for c in chains) + self.position_cost(node, cell)
        old_routes = {c: self.routes[c] for c in chains if c in self.routes}
        for c in old_routes:
            self.uncommit(c)
        self.unplace(node)
        best = None
        candidates = [(cell[0] + dx, cell[1] + dy) for dx in range(-2, 3) for dy in range(-2, 3)]
        self.rng.shuffle(candidates)
        for candidate in candidates:
            if not self.is_free_for_node(candidate) or candidate in self.occupied:
                continue
            self.place(node, candidate)
            trial = []
            total = self.position_cost(node, candidate)
            ok = True
            for c in chains:
                route = self.route(c)
                if route is None:
                    ok = False
                    break
                self.commit(c, route)
                trial.append(c)
                total += route["cost"]
                if total >= before - 1e-6 and (best is None or total >= best[0]):
                    ok = False
                    break
            if ok and (best is None or total < best[0]):
                best = (total, candidate, {c: self.routes[c] for c in trial})
            for c in trial:
                self.uncommit(c)
            self.unplace(node)
        if best is not None and best[0] < before - 1e-6:
            self.place(node, best[1])
            for c, route in best[2].items():
                self.commit(c, route)
            return 1
        self.place(node, cell)
        for c, route in old_routes.items():
            self.commit(c, route)
        return 0


def project(lat, lon, center):
    return (
        (lon - center[1]) * 111.32 * math.cos(math.radians(center[0])),
        (lat - center[0]) * 110.95,
    )


def even_out(stations, edges, settings):
    """Moves stations so that a hop is about the same length everywhere, keeping the overall shape."""
    ids = sorted(stations)
    index = {s: i for i, s in enumerate(ids)}
    n = len(ids)
    center = settings["center"]
    S = settings["compress_km"]
    init = np.zeros((n, 2))
    real = {}
    for s in ids:
        x, y = project(stations[s]["lat"], stations[s]["lon"], center)
        real[s] = (x, y)
        r = math.hypot(x, y) or 1e-6
        scale = S * math.log1p(r / S) / r
        init[index[s]] = (x * scale, y * scale)

    adjacency = [[] for _ in range(n)]
    for a, b in edges:
        km = math.hypot(real[a][0] - real[b][0], real[a][1] - real[b][1])
        middle = math.hypot((real[a][0] + real[b][0]) / 2, (real[a][1] + real[b][1]) / 2)
        # A hop gets more room near the center of the network, where the lines are dense.
        room = settings.get("edge_room", 0.8) + settings.get("center_room", 1.25) * math.exp(-((middle / settings.get("center_km", 11.0)) ** 2))
        for hub, extra, reach in settings.get("hubs", []):
            hx, hy = project(hub[0], hub[1], center)
            room += extra * math.exp(-((math.hypot((real[a][0] + real[b][0]) / 2 - hx, (real[a][1] + real[b][1]) / 2 - hy) / reach) ** 2))
        length = room * (1.0 + 0.25 * math.log(max(1.0, km / 1.3)))
        adjacency[index[a]].append((index[b], length))
        adjacency[index[b]].append((index[a], length))

    dist = np.full((n, n), np.inf)
    for source in range(n):
        heap = [(0.0, source)]
        d = dist[source]
        d[source] = 0
        while heap:
            du, u = heapq.heappop(heap)
            if du > d[u]:
                continue
            for v, w in adjacency[u]:
                if du + w < d[v]:
                    d[v] = du + w
                    heapq.heappush(heap, (du + w, v))
    edge_lengths = [np.linalg.norm(init[index[a]] - init[index[b]]) for a, b in edges]
    unit = float(np.median(edge_lengths)) or 1.0
    X = init / unit
    anchor = X.copy()
    finite = np.isfinite(dist)
    far = dist[finite].max() if finite.any() else 1.0
    # Separate pieces of a network keep their real distance from each other.
    D = np.where(finite, dist, np.linalg.norm(anchor[:, None] - anchor[None, :], axis=2) * 1.0)
    np.fill_diagonal(D, 1.0)
    W = 1.0 / (D ** 2)
    np.fill_diagonal(W, 0.0)
    anchor_weight = 0.012
    for _ in range(420):
        delta = X[:, None] - X[None, :]
        norm = np.linalg.norm(delta, axis=2)
        norm[norm < 1e-6] = 1e-6
        target = X[None, :] + (D / norm)[:, :, None] * delta
        numerator = (W[:, :, None] * target).sum(axis=1) + anchor_weight * anchor
        X = numerator / (W.sum(axis=1)[:, None] + anchor_weight)
    return {s: (float(X[index[s], 0]), float(X[index[s], 1])) for s in ids}


def build_chains(stations, line_edges, forced=frozenset()):
    """Splits the track network into runs between interchanges. Lines on the same run travel together."""
    edge_lines = defaultdict(set)
    for line, edges in line_edges.items():
        for a, b in edges:
            edge_lines[(min(a, b), max(a, b))].add(line)
    adjacency = defaultdict(set)
    for a, b in edge_lines:
        adjacency[a].add(b)
        adjacency[b].add(a)

    def is_key(s):
        neighbours = list(adjacency[s])
        if len(neighbours) != 2:
            return True
        first = edge_lines[(min(s, neighbours[0]), max(s, neighbours[0]))]
        second = edge_lines[(min(s, neighbours[1]), max(s, neighbours[1]))]
        return first != second

    keys = {s for s in adjacency if is_key(s)} | set(forced)
    seen = set()
    chains = []
    for key in sorted(keys):
        for first in sorted(adjacency[key]):
            edge = (min(key, first), max(key, first))
            if edge in seen:
                continue
            seen.add(edge)
            path = [key, first]
            while path[-1] not in keys:
                current = path[-1]
                following = [n for n in adjacency[current] if n != path[-2]]
                path.append(following[0])
                seen.add((min(current, following[0]), max(current, following[0])))
            chains.append({
                "a": path[0], "b": path[-1], "stations": path, "hops": len(path) - 1,
                "lines": sorted(edge_lines[edge]),
            })
    return chains, keys


def bends_of(path, placed, tolerance):
    """Stations where a run really changes direction, so a schematic keeps a city's shape."""
    points = [placed[s] for s in path]
    keep = set()

    def split(lo, hi):
        if hi <= lo + 1:
            return
        ax, ay = points[lo]
        bx, by = points[hi]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy) or 1e-6
        worst, where = 0.0, None
        for i in range(lo + 1, hi):
            distance = abs((points[i][0] - ax) * dy - (points[i][1] - ay) * dx) / length
            if distance > worst:
                worst, where = distance, i
        if worst > tolerance:
            keep.add(path[where])
            split(lo, where)
            split(where, hi)

    split(0, len(path) - 1)
    return keep


def layout_region(region, stations, line_edges, settings):
    edges = sorted({(min(a, b), max(a, b)) for es in line_edges.values() for a, b in es})
    used = {s for e in edges for s in e}
    subset = {s: stations[s] for s in used}
    print(f"[{region}] stations={len(subset)} edges={len(edges)}", flush=True)
    placed = even_out(subset, edges, settings)
    chains, keys = build_chains(subset, line_edges)
    forced = set()
    for chain in chains:
        forced |= bends_of(chain["stations"], placed, settings.get("bend_tolerance", 1.4))
    chains, keys = build_chains(subset, line_edges, forced)
    cells = settings["cells"]
    # Normalize so a hop takes `cells` grid steps.
    nodes = {k: (placed[k][0] * cells, placed[k][1] * cells) for k in keys}
    print(f"[{region}] key nodes={len(keys)} chains={len(chains)}", flush=True)
    layout = Layout(nodes, chains, cells)
    layout.snap()
    center = np.mean(list(nodes.values()), axis=0)
    order = sorted(range(len(chains)), key=lambda i: (chains[i]["hops"], math.hypot(nodes[chains[i]["a"]][0] - center[0], nodes[chains[i]["a"]][1] - center[1])))
    failed = layout.route_all(order)
    for attempt in range(8):
        if not failed:
            break
        # Whatever could not be routed goes first next time, and everything else works around it.
        print(f"[{region}] restart {attempt + 1}: {len(failed)} runs had no way through", flush=True)
        for index in list(layout.routes):
            layout.uncommit(index)
        order = failed + [i for i in order if i not in failed]
        failed = layout.route_all(order, 1.5)
    before = layout.total()
    t0 = time.time()
    passes = layout.improve(settings["search"])
    print(f"[{region}] cost {before:.0f} -> {layout.total():.0f}, {passes} passes in {time.time() - t0:.0f}s, failed={len(failed)}", flush=True)
    for index in failed:
        chain = chains[index]
        a, b = layout.pos[chain["a"]], layout.pos[chain["b"]]
        print("  unrouted", subset[chain["a"]]["name"], subset[chain["b"]]["name"])
        layout.routes[index] = {"cells": [a, b], "dirs": [0], "cost": 0}
    return layout, chains


def simplify(cells):
    out = [cells[0]]
    for i in range(1, len(cells) - 1):
        a, b, c = cells[i - 1], cells[i], cells[i + 1]
        if (b[0] - a[0], b[1] - a[1]) != (c[0] - b[0], c[1] - b[1]):
            out.append(b)
    out.append(cells[-1])
    return out


def walk(cells_simple, fraction):
    lengths = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(cells_simple, cells_simple[1:])]
    total = sum(lengths)
    target = fraction * total
    for (a, b), length in zip(zip(cells_simple, cells_simple[1:]), lengths):
        if target <= length or length == 0:
            t = target / length if length else 0
            return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        target -= length
    return cells_simple[-1]


def main(lines_path, out_path, only=None):
    search_override = os.environ.get("LAYOUT_SEARCH")
    if search_override:
        for settings in REGION_SETTINGS.values():
            settings["search"] = min(settings["search"], float(search_override))
    data = json.load(open(lines_path))
    stations = {s["id"]: s for s in data["stations"]}
    result = {}
    for region in REGION_SETTINGS:
        if only and region not in only:
            continue
        line_edges = {l["id"]: [tuple(e) for e in l["edges"]] for l in data["lines"] if l["region"] == region and l["edges"]}
        layout, chains = layout_region(region, stations, line_edges, REGION_SETTINGS[region])
        all_points = [c for r in layout.routes.values() for c in r["cells"]]
        min_x = min(p[0] for p in all_points)
        max_y = max(p[1] for p in all_points)
        positions = {}
        out_chains = []
        for index, chain in enumerate(chains):
            route = layout.routes[index]
            simple = simplify(route["cells"])
            # Screen y grows downward, and the grid's north is up.
            polyline = [[p[0] - min_x, max_y - p[1]] for p in simple]
            hops = len(chain["stations"]) - 1
            for i, station in enumerate(chain["stations"]):
                x, y = walk(polyline, i / hops)
                positions.setdefault(station, (round(x, 3), round(y, 3)))
            out_chains.append({"lines": chain["lines"], "stations": chain["stations"], "path": polyline})
        for node, cell in layout.pos.items():
            positions[node] = (cell[0] - min_x, max_y - cell[1])
        width = max(p[0] for p in positions.values())
        height = max(p[1] for p in positions.values())
        result[region] = {
            "width": width, "height": height,
            "positions": {str(k): list(v) for k, v in positions.items()},
            "chains": out_chains,
        }
        print(f"[{region}] {width}x{height} cells")
    json.dump(result, open(out_path, "w"), ensure_ascii=False)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], set(sys.argv[3:]) or None)
