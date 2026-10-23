#!/usr/bin/env python3
"""Builds the "Construction Detail Pack" add-on pack with Meshkit.

    python3 tools/build.py                  # writes ConstructionDetailPack/
    python3 tools/build.py --preview        # also writes preview sheets (rest + working pose) to build/preview/
    python3 tools/build.py excavator_20t    # only the listed vehicles

Meshkit is looked up at $MESHKIT_PATH, or ../Meshkit next to this project.

Every machine is an original design with its own dimensions, joints, work points, seats and wheels.
Each builder returns (model, spec, ram_errors). tools/templates/ holds the non-geometric settings
(speeds, capacities, hardness handling...) per machine type, taken over from Construction Machinery
Addon's own vehicles; everything geometric comes from `spec`.
"""
import copy
import math
import importlib
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import common  # noqa: E402
from common import mk  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TEMPLATES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates")
NS = "constructiondetail"
PACK = os.path.join(REPO, "ConstructionDetailPack")

# vehicle id: (module, builder, template vehicle, display name, working pose for the preview)
MACHINES = {
    "excavator_20t": ("excavator", "backhoe", "backhoe", "Backhoe 20t",
                      {"boom": 30, "arm": 60, "bucket": 80, "swing": 30, "load": 1}),
    "excavator_3t": ("excavator", "mini_backhoe", "mini_backhoe", "Mini Backhoe 3t",
                     {"boom": 30, "arm": 60, "bucket": 80, "swing": -30, "load": 1}),
    "rough_terrain_crane_25t": ("crane", "crane", "rough_terrain_crane", "Rough Terrain Crane 25t",
                                {"luff": 55, "extend": 12, "outrigger": 1, "rope": 6, "swing": 20}),
    "bulldozer_20t": ("dozer", "bulldozer", "bulldozer", "Bulldozer 20t", {"blade": 0.8, "load": 1}),
    "pile_driver_hammer": ("piling", "hammer", "pile_driver_hammer", "Drop Hammer Pile Driver",
                           {"mast": 0.0, "outrigger": 1, "hammer": 2.0}),
    "pile_driver_rotary": ("piling", "rotary", "pile_driver_rotary", "Rotary Pile Driver",
                           {"mast": 0.0, "outrigger": 1, "feed": 1.0}),
    "pile_driver_large": ("piling", "large", "pile_driver_large", "Large Rotary Pile Driver",
                          {"mast": 0.0, "outrigger": 1}),
    "dump_truck_10t": ("trucks", "dump_truck", "dump_truck", "Dump Truck 10t", {"bed": 50, "load": 1}),
    "mixer_truck": ("trucks", "mixer_truck", "mixer_truck", "Concrete Mixer Truck",
                    {"chute_swing": 40, "chute_tilt": 20}),
    "tractor_head": ("trucks", "tractor_head", "tractor_head", "Tractor Head 6x4", {}),
    "lowboy_trailer": ("trucks", "lowboy_trailer", "lowboy_trailer", "Low-bed Trailer", {}),
    "scissor_lift_8m": ("aerial", "scissor_lift", "scissor_lift", "Scissor Lift 8m", {"lift": 0.7, "extend": 0.9}),
    "bucket_truck_12m": ("aerial", "bucket_truck", "bucket_truck", "Insulated Bucket Truck 12m",
                         {"outrigger": 1, "luff": 50, "extend": 4, "swing": 60, "level": -50, "basket": 30}),
    "trailer_boom_lift_12m": ("aerial", "trailer_boom_lift", "trailer_boom_lift", "Trailer Boom Lift 12m",
                              {"outrigger": 1, "luff": 45, "knuckle": 110, "extend": 1.2, "swing": 40}),
    "spider_crane_3t": ("spider", "spider_crane", "spider_crane", "Spider Crane 3t",
                        {"outrigger": 1, "luff": 50, "extend": 3, "rope": 2, "swing": 30}),
    "full_rotation_crawler": ("fullrot", "crawler_type", "full_rotation_crawler", "Full-Rotation Machine (Crawler)",
                              {"outrigger": 1, "head": 1.6, "pile_top": 6.0, "pile_length": 6.0, "rpm": 5}),
    "full_rotation_unit": ("fullrot", "stationary_type", "full_rotation_unit", "Full-Rotation Machine (Stationary)",
                           {"outrigger": 1, "head": 1.6, "pile_top": 6.0, "pile_length": 6.0, "tool": 1}),
}
PREFIX = ""


def vehicle_json(vid, template, display, model_id, spec):
    with open(os.path.join(TEMPLATES, template + ".json"), encoding="utf-8") as f:
        data = copy.deepcopy(json.load(f))
    data["model"] = "%s:models/obj/%s.obj" % (NS, model_id)
    data["texture"] = "%s:textures/vehicle/%s.png" % (NS, model_id)
    data["display_name"] = PREFIX + display
    data["spawn_item"]["display_name"] = data["display_name"]
    data["scale"] = 1.0
    data.update(spec.get("top", {}))
    con = data["construction"]
    for key in ("joints", "work_points", "seat_parts"):
        con.pop(key, None)
    con.update(spec.get("construction", {}))
    for block, values in spec.get("settings", {}).items():
        con.setdefault(block, {}).update(values)
    return data


# ------------------------------------------------------------------ posed preview (approximates JointSolver)
def solve_joints(con, values, travel=0.0):
    """Python port of the addon's JointSolver (all joint modes and addon-drawn crawlers): one 4x4 model
    matrix per part. Used for previews and to check the JSON."""
    joints = con.get("joints", [])
    by_part = {j["part"]: j for j in joints}
    done, visiting = {}, set()

    def vec(j, k):
        return np.array([j.get(k + "_x", 0.0), j.get(k + "_y", 0.0), j.get(k + "_z", 0.0)], float)

    def matrix_of(part):
        if part not in by_part or part in visiting:
            return done.get(part, np.eye(4))
        return resolve(by_part[part])

    def point_now(p):
        v = np.array([p.get("x", 0.0), p.get("y", 0.0), p.get("z", 0.0), 1.0])
        return (matrix_of(p["part"]) @ v)[:3] if p.get("part") else v[:3]

    def rot4(axis, deg, pivot):
        R = np.array([common.rot(e, axis, deg) for e in np.eye(3)]).T
        M = np.eye(4); M[:3, :3] = R; M[:3, 3] = pivot - R @ pivot
        return M

    def resolve(j):
        if j["part"] in done:
            return done[j["part"]]
        visiting.add(j["part"])
        P = np.eye(4)
        if j.get("parent") in by_part and j["parent"] not in visiting:
            P = resolve(by_part[j["parent"]])
            if j.get("inherit_rotation", True) is False:
                piv = vec(j, "pivot")
                P = np.eye(4); P[:3, 3] = (resolve(by_part[j["parent"]]) @ np.append(piv, 1))[:3] - piv
        piv = vec(j, "pivot"); ax = vec(j, "axis")
        ax = ax / np.linalg.norm(ax) if np.linalg.norm(ax) > 1e-9 else np.array([1.0, 0, 0])
        mode = j.get("mode", "rotate"); f = j.get("factor", 1.0)
        off = j.get("offset", 1.0 if mode == "scale" else 0.0)
        v = values.get(j.get("channel"), 0.0) * f + off if j.get("channel") else off
        lo, hi = j.get("min", -1e6), j.get("max", 1e6)
        v = min(max(v, min(lo, hi)), max(lo, hi))
        M = np.eye(4)
        if mode == "rotate" and j.get("channel"):
            M = rot4(ax, v, piv)
        elif mode == "slide":
            M[:3, 3] = ax * v
        elif mode == "scale" and j.get("channel"):
            sc = max(0.02, v)
            S = np.eye(4); S[:3, :3] = np.eye(3) + (sc - 1.0) * np.outer(ax, ax)
            T = np.eye(4); T[:3, 3] = piv
            Ti = np.eye(4); Ti[:3, 3] = -piv
            M = T @ S @ Ti
        elif mode == "aim":
            t = j["target"]; trest = np.array([t["x"], t["y"], t["z"]])
            now = (np.linalg.inv(P) @ np.append(point_now(t), 1))[:3] - piv
            rest = trest - piv
            now -= ax * now.dot(ax); rest -= ax * rest.dot(ax)
            ang = math.degrees(math.atan2(np.cross(rest, now).dot(ax), rest.dot(now)))
            M = rot4(ax, ang + j.get("offset", 0.0), piv)
        elif mode == "reach":
            t, a = j["target"], j["anchor"]
            d_now = np.linalg.norm(point_now(t) - point_now(a))
            d_rest = np.linalg.norm(np.array([t["x"], t["y"], t["z"]]) - np.array([a["x"], a["y"], a["z"]]))
            M[:3, 3] = ax * ((d_now - d_rest) * f + j.get("offset", 0.0))
        elif mode == "belt":
            M = rot4(ax, f * travel + j.get("offset", 0.0), piv)
        elif mode == "scissor" and j.get("channel"):
            rest = min(max(off, -1.0), 1.0)
            M = rot4(ax, math.degrees(math.asin(min(max(v, -1.0), 1.0)) - math.asin(rest)), piv)
        elif mode == "show" and v < 0.999:
            T = np.eye(4); T[:3, 3] = piv
            Ti = np.eye(4); Ti[:3, 3] = -piv
            M = T @ np.diag([1e-3, 1e-3, 1e-3, 1.0]) @ Ti
        R = P @ M
        done[j["part"]] = R
        visiting.discard(j["part"])
        return R

    for j in joints:
        resolve(j)
    for c in con.get("crawlers", []):
        path = [(p["y"], p["z"]) for p in c["path"]]
        total = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(path, path[1:] + path[:1]))
        count = int(round(total / c["link_spacing"]))
        rest = common.path_placements(path, c["link_spacing"], count)
        now = common.path_placements([(y, z) for y, z in path], c["link_spacing"], count) if travel == 0 else None
        Pm = matrix_of(c["parent"]) if c.get("parent") else np.eye(4)
        for i, (y0, z0, a0) in enumerate(rest):
            if now is not None:
                y1, z1, a1 = now[i]
            else:
                # shift every link by the travel along the closed path
                y1, z1, a1 = place_at(path, i * c["link_spacing"] + travel)
            T0 = np.eye(4); T0[:3, 3] = [0, -y0, -z0]
            T1 = np.eye(4); T1[:3, 3] = [0, y1, z1]
            done[c["link_prefix"] + str(i)] = Pm @ T1 @ rot4(np.array([1.0, 0, 0]), a1 - a0, np.zeros(3)) @ T0
    return done


def place_at(path, raw):
    n = len(path)
    cum = [0.0]
    for i in range(n):
        a, b = path[i], path[(i + 1) % n]
        cum.append(cum[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    d = raw % cum[-1]
    k = 0
    while k < n - 1 and cum[k + 1] < d:
        k += 1
    a, b = path[k], path[(k + 1) % n]
    f = (d - cum[k]) / max(cum[k + 1] - cum[k], 1e-9)
    return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))


def pose_transforms(con, values, travel=0.0):
    mats = solve_joints(con, values, travel)
    return {part: (lambda MM: (lambda P: (np.c_[P, np.ones(len(P))] @ MM.T)[:, :3]))(M) for part, M in mats.items()}


def preview_model(m, data, travel=1.3):
    """A copy for previews with the base mod's crawler links laid round their paths (the game draws one
    link model many times; the OBJ holds it once)."""
    import copy as _copy
    pm = _copy.deepcopy(m)
    for track in data.get("crawler_tracks", []):
        if track["part"] not in pm.parts:
            continue
        path = [(p["y"], p["z"]) for p in track["path"]]
        total = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(path, path[1:] + path[:1]))
        sp = track["link_spacing"]
        shells = pm.parts[track["part"]].shells
        placed = []
        for i in range(int(round(total / sp))):
            y, z, ang = place_at(path, i * sp + travel)
            for sh in shells:
                c = sh.copy()
                c.rotate(mk.rot_axis((1, 0, 0), ang + 90.0), pivot=(0, 0, 0))
                c.translate(0, y, z)
                placed.append(c)
        pm.parts[track["part"]].shells = placed
    return pm


def main():
    preview = "--preview" in sys.argv
    only = [a for a in sys.argv[1:] if not a.startswith("--")]
    scratch = os.environ.get("PREVIEW_DIR", os.path.join(REPO, "build", "preview"))
    obj_dir = os.path.join(PACK, "assets", NS, "models", "obj")
    tex_dir = os.path.join(PACK, "assets", NS, "textures", "vehicle")
    veh_dir = os.path.join(PACK, "data", NS, "vehicles")
    for d in (obj_dir, tex_dir, veh_dir):
        os.makedirs(d, exist_ok=True)
    from PIL import Image
    for vid, (mod, fn, template, display, pose) in MACHINES.items():
        if only and vid not in only:
            continue
        m, spec, errors = getattr(importlib.import_module(mod), fn)()
        common.finish(m, 1024, stripes=(1, 0))
        mk.save(m, os.path.join(obj_dir, vid + ".obj"),
                header="# %s - Construction Detail Pack (generated with Meshkit by tools/build.py)" % vid)
        Image.fromarray(m.texture, "RGBA").save(os.path.join(tex_dir, vid + ".png"))
        data = vehicle_json(vid, template, display, vid, spec)
        with open(os.path.join(veh_dir, vid + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(common.report(m, vid), "ram stages:", list(errors))
        if preview:
            from meshkit import view
            os.makedirs(scratch, exist_ok=True)
            views = ["iso", "left", "front", "top"]
            m = preview_model(m, data)
            sheets = [view.sheet(m, views=views, mode="texture")]
            if pose:
                tf = pose_transforms(data["construction"], pose, travel=1.3)
                sheets.append(view.sheet(m, views=views, mode="texture", transforms=tf))
            img = Image.new("RGB", (max(s.width for s in sheets), sum(s.height for s in sheets)))
            y = 0
            for s in sheets:
                img.paste(s, (0, y)); y += s.height
            img.save(os.path.join(scratch, vid + ".png"))


if __name__ == "__main__":
    main()
