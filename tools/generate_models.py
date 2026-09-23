#!/usr/bin/env python3
"""Generates the sample machines' OBJ models, palette texture and vehicle JSONs.

The models are simple box/prism shapes - placeholders good enough to show every moving part.
Geometry (pivots, work points) is written into the JSON from the same numbers used to build
the meshes, so model and joints can never disagree. Replace the OBJs with proper models at any
time: keep the group names and pivots, or edit the JSON to match.

Conventions (base mod): model units = blocks at scale 1.0, +Z forward, +X LEFT, +Y up,
origin = centre of the vehicle on the ground.

Run from the project root:  python3 tools/generate_models.py
Needs Pillow (pip install pillow) for the texture.
"""
import json
import math
import os

NS = "constructionaddon"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
OBJ_DIR = os.path.join(ROOT, "assets", NS, "models", "obj")
TEX_DIR = os.path.join(ROOT, "assets", NS, "textures", "vehicle")
VEH_DIR = os.path.join(ROOT, "data", NS, "vehicles")

# ---------------------------------------------------------------- palette
PALETTE = [
    (232, 178, 30),   # 0 machine yellow
    (58, 58, 58),     # 1 dark grey (tracks, chassis)
    (21, 21, 21),     # 2 tyre black
    (138, 143, 150),  # 3 steel
    (111, 168, 200),  # 4 glass
    (224, 106, 27),   # 5 orange
    (232, 232, 232),  # 6 white
    (122, 82, 48),    # 7 earth
    (169, 169, 162),  # 8 concrete grey
    (176, 37, 37),    # 9 red
    (200, 200, 200),  # 10 light steel
    (156, 122, 72),   # 11 wood
]
GRID = 8  # 8 x 8 cells of 8 px on a 64 x 64 texture


def uv(color):
    cx = (color % GRID + 0.5) / GRID
    cy = (color // GRID + 0.5) / GRID
    return cx, 1.0 - cy


def write_texture():
    try:
        from PIL import Image
    except ImportError:
        print("Pillow not installed - texture not written")
        return
    img = Image.new("RGBA", (64, 64), (255, 0, 255, 255))
    for i, c in enumerate(PALETTE):
        x0, y0 = (i % GRID) * 8, (i // GRID) * 8
        for x in range(x0, x0 + 8):
            for y in range(y0, y0 + 8):
                img.putpixel((x, y), c + (255,))
    os.makedirs(TEX_DIR, exist_ok=True)
    img.save(os.path.join(TEX_DIR, "construction.png"))


# ---------------------------------------------------------------- mesh building
def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def mul(a, k):
    return (a[0] * k, a[1] * k, a[2] * k)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def norm(a):
    length = math.sqrt(a[0] ** 2 + a[1] ** 2 + a[2] ** 2)
    return (a[0] / length, a[1] / length, a[2] / length) if length > 1e-9 else (0.0, 1.0, 0.0)


class Mesh:
    def __init__(self):
        self.groups = {}  # name -> list of (verts[4 or n], color)
        self.order = []

    def face(self, group, verts, color):
        if group not in self.groups:
            self.groups[group] = []
            self.order.append(group)
        self.groups[group].append((verts, color))

    def box(self, group, x0, y0, z0, x1, y1, z1, color):
        x0, x1 = min(x0, x1), max(x0, x1)
        y0, y1 = min(y0, y1), max(y0, y1)
        z0, z1 = min(z0, z1), max(z0, z1)
        self.oriented_box(group, ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2),
                          (1, 0, 0), (0, 1, 0), (0, 0, 1),
                          (x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2, color)

    def oriented_box(self, group, c, ax, ay, az, hx, hy, hz, color):
        def p(sx, sy, sz):
            return add(add(add(c, mul(ax, sx * hx)), mul(ay, sy * hy)), mul(az, sz * hz))
        # Each face listed counter-clockwise seen from outside.
        faces = [
            [p(1, -1, -1), p(1, 1, -1), p(1, 1, 1), p(1, -1, 1)],      # +x
            [p(-1, -1, 1), p(-1, 1, 1), p(-1, 1, -1), p(-1, -1, -1)],  # -x
            [p(-1, 1, -1), p(-1, 1, 1), p(1, 1, 1), p(1, 1, -1)],      # +y
            [p(-1, -1, 1), p(-1, -1, -1), p(1, -1, -1), p(1, -1, 1)],  # -y
            [p(-1, -1, 1), p(1, -1, 1), p(1, 1, 1), p(-1, 1, 1)],      # +z
            [p(1, -1, -1), p(-1, -1, -1), p(-1, 1, -1), p(1, 1, -1)],  # -z
        ]
        for f in faces:
            self.face(group, f, color)

    def beam(self, group, a, b, width, height, color, up=(0, 1, 0)):
        """A box from point a to point b; width along the side axis, height along 'up'."""
        axis = sub(b, a)
        length = math.sqrt(sum(v * v for v in axis))
        az = norm(axis)
        ax = norm(cross(up, az))
        if abs(sum(v * v for v in cross(up, az))) < 1e-9:
            ax = (1, 0, 0)
        ay = norm(cross(az, ax))
        c = mul(add(a, b), 0.5)
        self.oriented_box(group, c, ax, ay, az, width / 2, height / 2, length / 2, color)

    def prism(self, group, a, b, radius, sides, colors):
        """A closed n-sided prism from a to b (wheels, drums). colors cycles per side face."""
        az = norm(sub(b, a))
        helper = (0, 1, 0) if abs(az[1]) < 0.9 else (1, 0, 0)
        ax = norm(cross(helper, az))
        ay = cross(az, ax)
        ring_a, ring_b = [], []
        for i in range(sides):
            t = 2 * math.pi * i / sides
            off = add(mul(ax, math.cos(t) * radius), mul(ay, math.sin(t) * radius))
            ring_a.append(add(a, off))
            ring_b.append(add(b, off))
        for i in range(sides):
            j = (i + 1) % sides
            self.face(group, [ring_a[i], ring_a[j], ring_b[j], ring_b[i]], colors[i % len(colors)])
        self.face(group, list(reversed(ring_a)), colors[0])
        self.face(group, ring_b, colors[0])

    def wheel(self, group, cx, cy, cz, radius, width, color=2):
        self.prism(group, (cx - width / 2, cy, cz), (cx + width / 2, cy, cz), radius, 10, [color, 1])

    def write(self, path, header):
        lines = ["# " + header, "# Generated by tools/generate_models.py", ""]
        verts, normals, uvs, body = [], [], [], []
        uv_index = {}
        for group in self.order:
            body.append("g " + group)
            for face, color in self.groups[group]:
                n = norm(cross(sub(face[1], face[0]), sub(face[2], face[0])))
                normals.append(n)
                ni = len(normals)
                if color not in uv_index:
                    uvs.append(uv(color))
                    uv_index[color] = len(uvs)
                ti = uv_index[color]
                idx = []
                for v in face:
                    verts.append(v)
                    idx.append("%d/%d/%d" % (len(verts), ti, ni))
                body.append("f " + " ".join(idx))
        for v in verts:
            lines.append("v %.5f %.5f %.5f" % v)
        for t in uvs:
            lines.append("vt %.5f %.5f" % t)
        for n in normals:
            lines.append("vn %.5f %.5f %.5f" % n)
        lines += body
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------- shared parts
def tracks(m, inner=0.75, outer=1.45, length=1.9, height=0.75):
    for sign in (1, -1):
        m.box("body", sign * inner, 0.0, -length, sign * outer, height, length, 1)
        m.box("body", sign * (inner + 0.05), 0.2, -length - 0.15, sign * (outer - 0.05), height - 0.2, -length, 3)
        m.box("body", sign * (inner + 0.05), 0.2, length, sign * (outer - 0.05), height - 0.2, length + 0.15, 3)


def cab(m, group, x0, x1, y0, y1, z0, z1, color=0, front=True, side_sign=1):
    m.box(group, x0, y0, z0, x1, y1, z1, color)
    gy0 = y0 + (y1 - y0) * 0.45
    if front:
        m.box(group, x0 + 0.05, gy0, z1, x1 - 0.05, y1 - 0.1, z1 + 0.03, 4)
    side = x1 if side_sign > 0 else x0
    m.box(group, side, gy0, z0 + 0.1, side + 0.03 * side_sign, y1 - 0.1, z1 - 0.1, 4)


def joint(part, pivot, axis, mode="rotate", channel=None, parent=None, **extra):
    j = {"part": part}
    if parent:
        j["parent"] = parent
    j.update({"pivot_x": round(pivot[0], 4), "pivot_y": round(pivot[1], 4), "pivot_z": round(pivot[2], 4),
              "axis_x": axis[0], "axis_y": axis[1], "axis_z": axis[2], "mode": mode})
    if channel:
        j["channel"] = channel
    j.update(extra)
    return j


def point(part, p):
    d = {"x": round(p[0], 4), "y": round(p[1], 4), "z": round(p[2], 4)}
    if part:
        d = {"part": part, **d}
    return d


def base_json(name, model, tier, **kw):
    data = {
        "entity_type": NS + ":construction_machine",
        "model": NS + ":models/obj/" + model + ".obj",
        "texture": NS + ":textures/vehicle/construction.png",
        "display_name": name,
        "scale": 1.0,
        "width": 3.0,
        "height": 3.0,
        "max_speed": 0.35,
        "acceleration": 0.04,
        "turn_speed": 2.0,
        "step_height": 1.0,
        "gravity": -0.04,
        "reverse_throttle": 0.5,
        "weight_type": "tank",
        "max_health": 120.0,
        "armor_damage_factor": 1.0,
        "damage_factor": 1.0,
        "max_fuel": 1200.0,
        "inventory_size": 9,
        "spawn_item": {"display_name": name, "tier": tier},
    }
    data.update(kw)
    return data


def wheel_parts(entries):
    out = []
    for part, x, y, z, steer in entries:
        w = {"part": part, "pivot_x": x, "pivot_y": y, "pivot_z": z}
        if steer:
            w["steer_angle"] = steer
        out.append(w)
    return out


def save_json(filename, data):
    os.makedirs(VEH_DIR, exist_ok=True)
    with open(os.path.join(VEH_DIR, filename + ".json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------- backhoe
def backhoe():
    m = Mesh()
    tracks(m)
    m.box("body", -0.75, 0.25, -1.2, 0.75, 0.7, 1.2, 3)
    swing_pivot = (0.0, 0.8, 0.0)
    m.box("$upper", -1.3, 0.8, -1.9, 1.3, 1.0, 1.4, 0)
    m.box("$upper", -1.3, 1.0, -1.9, 0.2, 1.9, 0.3, 0)
    m.box("$upper", -1.3, 0.9, -2.25, 1.3, 1.7, -1.9, 1)
    cab(m, "$upper", 0.3, 1.25, 1.0, 2.5, 0.1, 1.35)
    B = (-0.2, 1.3, 1.1)
    A = (-0.2, 3.5, 3.4)
    K = (-0.2, 1.4, 4.4)
    T = (-0.2, 0.6, 4.95)
    m.box("$upper", -0.55, 1.0, 0.8, 0.15, 1.6, 1.4, 1)  # boom foot
    m.beam("$boom", B, A, 0.45, 0.55, 0)
    m.beam("$boom", add(B, (0, 0.2, 0.4)), add(mul(add(B, A), 0.5), (0, 0.35, 0)), 0.18, 0.18, 10)  # cylinder
    m.beam("$arm", A, K, 0.35, 0.42, 0)
    m.beam("$arm", A, add(A, (0, 0.45, -0.45)), 0.3, 0.3, 0)
    # bucket: back plate, bottom lip, sides, teeth
    m.beam("$bucket", K, T, 0.9, 0.08, 3, up=(1, 0, 0))
    lip = (-0.2, 0.75, 4.45)
    m.beam("$bucket", T, lip, 0.9, 0.08, 3, up=(1, 0, 0))
    for sx in (0.25, -0.65):
        m.box("$bucket", sx - 0.03, 0.6, 4.45, sx + 0.03, 1.35, 4.95, 3)
    m.box("$bucket", -0.6, 0.52, 4.9, 0.2, 0.62, 5.05, 1)
    load_pivot = (-0.2, 0.7, 4.7)
    m.box("$bucket_load", -0.55, 0.7, 4.5, 0.15, 1.1, 4.9, 7)
    m.write(os.path.join(OBJ_DIR, "backhoe.obj"), "Backhoe")

    joints = [
        joint("$upper", swing_pivot, (0, 1, 0), channel="swing"),
        joint("$boom", B, (-1, 0, 0), channel="boom", parent="$upper"),
        joint("$arm", A, (-1, 0, 0), channel="arm", parent="$boom"),
        joint("$bucket", K, (1, 0, 0), channel="bucket", parent="$arm"),
        joint("$bucket_load", load_pivot, (0, 1, 0), mode="scale", channel="load", parent="$bucket", offset=0.0),
    ]
    construction = {
        "machine": "excavator",
        "joints": joints,
        "work_points": {"bucket_tip": point("$bucket", T)},
        "seat_parts": [{"seat": 0, "part": "$upper"}],
        "excavator": {
            "swing_speed": 2.5, "boom_speed": 1.5, "arm_speed": 2.0,
            "boom_min": -40.0, "boom_max": 35.0, "arm_min": -50.0, "arm_max": 110.0,
            "bucket_min": -60.0, "bucket_max": 110.0, "curl_speed": 4.0,
            "capacity": 4, "dig_radius": 0.8, "base_dig_ticks": 4.0, "dump_angle": -20.0, "dump_interval": 3,
            "dump_spread_radius": 4, "dump_slope": 0.75,
            "resistance": {"hardness_scale": 1.0, "refusal_hardness": 3.5}
        }
    }
    seats = [{"name": "operator", "offset_x": 0.78, "offset_y": 0.95, "offset_z": 0.7, "driver": True}]
    save_json("backhoe", base_json("Backhoe", "backhoe", 2, seats=seats, construction=construction,
                                   max_speed=0.3, turn_speed=2.5, max_health=140.0))

    mini = json.loads(json.dumps(construction))
    mini["excavator"]["capacity"] = 2
    save_json("mini_backhoe", base_json("Mini Backhoe", "backhoe", 1, seats=seats, construction=mini,
                                        scale=0.6, max_speed=0.28, turn_speed=3.0, max_health=70.0,
                                        step_height=0.6, max_fuel=600.0))


# ---------------------------------------------------------------- crane
def crane():
    m = Mesh()
    m.box("body", -1.1, 0.6, -3.0, 1.1, 1.4, 3.0, 0)
    m.box("body", -0.5, 0.35, -3.1, 0.5, 0.7, 3.1, 1)
    wheels = []
    for i, (x, z, steer) in enumerate([(1.2, 2.0, 25.0), (-1.2, 2.0, 25.0), (1.2, -2.0, 0), (-1.2, -2.0, 0)]):
        part = "$wheel%d" % i
        m.wheel(part, x, 0.6, z, 0.6, 0.5)
        wheels.append((part, x, 0.6, z, steer))
    # Outriggers: beams inside the carrier that slide out sideways, with pads.
    for side, sign in (("l", 1), ("r", -1)):
        g = "$outrigger_" + side
        for z in (-2.7, 2.7):
            m.box(g, sign * 0.1, 0.75, z - 0.2, sign * 1.3, 1.0, z + 0.2, 3)
            m.box(g, sign * 1.15, 0.0, z - 0.3, sign * 1.45, 0.8, z + 0.3, 1)
    swing_pivot = (0.0, 1.4, -0.5)
    m.box("$upper", -1.0, 1.4, -2.2, 1.0, 1.7, 1.0, 0)
    m.box("$upper", -1.0, 1.7, -2.2, 0.2, 2.2, -1.2, 1)
    cab(m, "$upper", 0.35, 1.1, 1.7, 2.7, -0.3, 1.0)
    luff_pivot = (0.0, 2.15, -1.8)
    m.box("$upper", -0.45, 1.7, -2.1, 0.45, 2.3, -1.5, 3)
    m.box("$boom", -0.35, 1.85, -2.3, 0.35, 2.45, 3.5, 0)
    m.box("$boom2", -0.28, 1.9, -1.5, 0.28, 2.4, 4.2, 6)
    m.box("$boom3", -0.22, 1.95, -1.0, 0.22, 2.35, 4.9, 0)
    m.box("$boom3", -0.25, 1.75, 4.9, 0.25, 2.45, 5.2, 3)
    tip = (0.0, 1.75, 5.05)
    m.box("$rope", -0.03, tip[1] - 1.0, 5.02, 0.03, tip[1], 5.08, 1)
    m.box("$hook", -0.2, tip[1] - 0.55, 4.85, 0.2, tip[1], 5.25, 5)
    m.box("$hook", -0.05, tip[1] - 0.9, 5.0, 0.05, tip[1] - 0.55, 5.1, 3)
    m.write(os.path.join(OBJ_DIR, "rough_terrain_crane.obj"), "Rough terrain crane")

    joints = [
        joint("$outrigger_l", (0, 0, 0), (1, 0, 0), mode="slide", channel="outrigger", factor=1.2),
        joint("$outrigger_r", (0, 0, 0), (-1, 0, 0), mode="slide", channel="outrigger", factor=1.2),
        joint("$upper", swing_pivot, (0, 1, 0), channel="swing"),
        joint("$boom", luff_pivot, (-1, 0, 0), channel="luff", parent="$upper"),
        joint("$boom2", luff_pivot, (0, 0, 1), mode="slide", channel="extend", factor=0.5, parent="$boom"),
        joint("$boom3", luff_pivot, (0, 0, 1), mode="slide", channel="extend", factor=0.5, parent="$boom2"),
        joint("$rope", tip, (0, 1, 0), mode="scale", channel="rope", parent="$boom3", offset=0.0,
              inherit_rotation=False),
        joint("$hook", tip, (0, -1, 0), mode="slide", channel="rope", parent="$boom3", inherit_rotation=False),
    ]
    construction = {
        "machine": "crane",
        "joints": joints,
        "work_points": {"boom_tip": point("$boom3", tip)},
        "seat_parts": [{"seat": 0, "part": "$upper"}],
        "crane": {
            "swing_speed": 1.5, "luff_speed": 0.6, "luff_min": 0.0, "luff_max": 78.0,
            "extend_speed": 0.06, "extend_max": 8.0, "rope_speed": 0.15, "rope_min": 0.5, "rope_max": 40.0,
            "outrigger_speed": 0.04, "require_outriggers": True, "grab_radius": 1.5, "max_lift_width": 6.0,
            "hook_drop": 0.9
        }
    }
    seats = [{"name": "operator", "offset_x": 0.72, "offset_y": 1.75, "offset_z": 0.35, "driver": True}]
    save_json("rough_terrain_crane", base_json("Rough Terrain Crane", "rough_terrain_crane", 3, seats=seats,
                                               construction=construction, wheel_parts=wheel_parts(wheels),
                                               weight_type="car", max_speed=0.45, turn_speed=2.0, max_health=160.0))


# ---------------------------------------------------------------- bulldozer
def bulldozer():
    m = Mesh()
    tracks(m, 0.8, 1.5, 1.8, 0.8)
    m.box("body", -0.8, 0.4, -1.6, 0.8, 1.6, 1.5, 0)
    m.box("body", -0.6, 1.6, 0.2, 0.6, 1.75, 1.5, 1)
    cab(m, "body", -0.7, 0.7, 1.6, 2.8, -1.4, -0.2)
    m.box("body", 0.45, 1.6, 0.6, 0.6, 2.4, 0.75, 1)  # exhaust
    m.box("$blade", -1.6, 0.0, 2.1, 1.6, 1.2, 2.3, 0)
    m.box("$blade", -1.6, 0.0, 2.25, 1.6, 0.12, 2.4, 3)  # cutting edge
    for sx in (1.25, -1.25):
        m.beam("$blade", (sx, 0.5, 0.2), (sx, 0.5, 2.1), 0.18, 0.2, 1)
    m.box("$blade_load", -1.4, 0.0, 2.35, 1.4, 0.9, 3.0, 7)
    m.write(os.path.join(OBJ_DIR, "bulldozer.obj"), "Bulldozer")

    joints = [
        joint("$blade", (0, 0, 0), (0, 1, 0), mode="slide", channel="blade"),
        joint("$blade_load", (0, 0, 2.6), (0, 1, 0), mode="scale", channel="load", parent="$blade", offset=0.0),
    ]
    construction = {
        "machine": "bulldozer",
        "joints": joints,
        "bulldozer": {
            "blade_front_z": 2.3, "blade_width": 3.2, "blade_bottom_y": 0.0, "blade_height": 1.2,
            "blade_min": -2.0, "blade_max": 1.5, "blade_speed": 0.04, "capacity": 16, "max_cuts_per_tick": 2,
            "full_load_speed": 0.5, "cut_drag": 0.04, "unload_interval": 3, "min_work_speed": 0.02,
            "dump_spread_radius": 3, "dump_slope": 0.75,
            "resistance": {"hardness_scale": 1.0, "refusal_hardness": 2.0}
        }
    }
    seats = [{"name": "operator", "offset_x": 0.0, "offset_y": 1.65, "offset_z": -0.8, "driver": True}]
    save_json("bulldozer", base_json("Bulldozer", "bulldozer", 2, seats=seats, construction=construction,
                                     max_speed=0.3, turn_speed=2.5, max_health=160.0))


# ---------------------------------------------------------------- pile drivers
MAST_PIVOT = (0.0, 1.0, 1.6)
PILE_POINT = (0.0, 1.0, 2.35)


def pile_driver_base(m):
    tracks(m, 0.75, 1.45, 1.9, 0.75)
    m.box("body", -0.9, 0.7, -1.8, 0.9, 1.7, 1.2, 0)
    m.box("body", -0.9, 0.8, -2.2, 0.9, 1.5, -1.8, 1)
    cab(m, "body", 0.2, 1.0, 1.7, 2.7, -0.4, 0.8)
    m.box("body", -0.35, 0.7, 1.2, 0.35, 1.2, 1.7, 3)  # mast foot
    m.box("$mast", -0.2, 1.0, 1.6, 0.2, 8.5, 1.9, 5)
    m.box("$mast", -0.35, 8.3, 1.5, 0.35, 8.6, 2.6, 3)  # crown
    m.box("$mast", -0.25, 1.0, 1.9, 0.25, 1.3, 2.1, 3)  # lower guide


def pile_driver_json(name, model, tier, mode_joints, pile_settings):
    joints = [joint("$mast", MAST_PIVOT, (1, 0, 0), channel="mast", factor=80.0, offset=-80.0)] + mode_joints
    construction = {
        "machine": "pile_driver",
        "joints": joints,
        "work_points": {"pile_point": point("$mast", PILE_POINT)},
        "pile_driver": pile_settings,
    }
    seats = [{"name": "operator", "offset_x": 0.6, "offset_y": 1.65, "offset_z": 0.2, "driver": True}]
    save_json(model, base_json(name, model, tier, seats=seats, construction=construction,
                               max_speed=0.25, turn_speed=2.0, max_health=140.0, inventory_size=18))


def pile_drivers():
    m = Mesh()
    pile_driver_base(m)
    m.box("$hammer", -0.3, 0.1, 2.05, 0.3, 1.1, 2.65, 1)
    m.box("$hammer", -0.05, 1.1, 2.3, 0.05, 7.9, 2.4, 3)  # winch rope (rides with the hammer)
    m.write(os.path.join(OBJ_DIR, "pile_driver_hammer.obj"), "Drop hammer pile driver")
    pile_driver_json("Pile Driver (Drop Hammer)", "pile_driver_hammer", 3,
                     [joint("$hammer", (0, 0, 0), (0, 1, 0), mode="slide", channel="hammer", parent="$mast")],
                     {"mode": "hammer", "max_depth": 32, "default_depth": 8, "mast_speed": 0.02,
                      "blows_per_block": 2.0, "max_blows_per_block": 40, "lift_ticks": 20, "drop_ticks": 5,
                      "lift_height": 2.0,
                      "resistance": {"hardness_scale": 1.0, "refusal_hardness": 20.0}})

    m = Mesh()
    pile_driver_base(m)
    m.box("$rotary_head", -0.4, 1.0, 1.95, 0.4, 1.8, 2.75, 3)
    m.box("$rotary_head", -0.3, 1.8, 2.05, 0.3, 2.2, 2.65, 1)
    m.box("$auger", -0.08, -0.6, 2.27, 0.08, 1.0, 2.43, 10)
    for i in range(10):
        y = -0.55 + i * 0.15
        a = i * 0.9
        ox, oz = math.cos(a) * 0.22, math.sin(a) * 0.22
        m.box("$auger", ox - 0.1, y, 2.35 + oz - 0.1, ox + 0.1, y + 0.06, 2.35 + oz + 0.1, 5)
    m.write(os.path.join(OBJ_DIR, "pile_driver_rotary.obj"), "Rotary press-in pile driver")
    pile_driver_json("Pile Driver (Rotary)", "pile_driver_rotary", 4,
                     [joint("$rotary_head", (0, 0, 0), (0, 1, 0), mode="slide", channel="feed", factor=-1.0,
                            parent="$mast"),
                      joint("$auger", (0, 0, 2.35), (0, 1, 0), mode="spin", channel="rpm", factor=0.3,
                            parent="$rotary_head")],
                     {"mode": "rotary", "max_depth": 40, "default_depth": 12, "mast_speed": 0.02,
                      "ticks_per_block": 40.0, "rpm": 30.0, "min_rpm": 5.0, "rpm_response": 0.15,
                      "resistance": {"hardness_scale": 1.0, "refusal_hardness": 25.0}})


# ---------------------------------------------------------------- trucks
def truck_base(m, cab_color, length_rear=-3.0):
    m.box("body", -0.6, 0.5, length_rear, 0.6, 1.1, 3.4, 1)
    cab(m, "body", -1.2, 1.2, 1.0, 3.0, 1.6, 3.4, cab_color)
    wheels = []
    for i, (x, z, steer) in enumerate([(1.1, 2.4, 30.0), (-1.1, 2.4, 30.0), (1.1, -1.0, 0), (-1.1, -1.0, 0),
                                       (1.1, -2.2, 0), (-1.1, -2.2, 0)]):
        part = "$wheel%d" % i
        m.wheel(part, x, 0.55, z, 0.55, 0.45)
        wheels.append((part, x, 0.55, z, steer))
    return wheels


def dump_truck():
    m = Mesh()
    wheels = truck_base(m, 5)
    bed_pivot = (0.0, 1.3, -2.9)
    m.box("$bed", -1.2, 1.3, -2.9, 1.2, 1.45, 1.3, 0)
    m.box("$bed", 1.1, 1.45, -2.9, 1.2, 2.4, 1.3, 0)
    m.box("$bed", -1.2, 1.45, -2.9, -1.1, 2.4, 1.3, 0)
    m.box("$bed", -1.2, 1.45, 1.2, 1.2, 2.6, 1.3, 0)
    m.box("$bed", -1.2, 1.45, -2.95, 1.2, 2.3, -2.85, 3)
    m.box("$bed_load", -1.1, 1.45, -2.8, 1.1, 2.35, 1.2, 7)
    m.write(os.path.join(OBJ_DIR, "dump_truck.obj"), "Dump truck")
    joints = [
        joint("$bed", bed_pivot, (-1, 0, 0), channel="bed"),
        joint("$bed_load", (0, 1.45, 0), (0, 1, 0), mode="scale", channel="load", parent="$bed", offset=0.0),
    ]
    construction = {
        "machine": "dump_truck",
        "joints": joints,
        "work_points": {"discharge": point("$bed", (0.0, 1.5, -3.3))},
        "dump_truck": {
            "bed_min_x": -1.1, "bed_min_y": 1.2, "bed_min_z": -2.9, "bed_max_x": 1.1, "bed_max_y": 4.5,
            "bed_max_z": 1.3, "bed_speed": 1.5, "bed_max_angle": 55.0, "dump_start_angle": 20.0,
            "dump_interval_slow": 8, "dump_interval_fast": 2, "capacity": 256, "discharge_point": "discharge",
            "dump_spread_radius": 4, "dump_slope": 0.6
        }
    }
    seats = [{"name": "driver", "offset_x": -0.6, "offset_y": 1.2, "offset_z": 2.4, "driver": True}]
    save_json("dump_truck", base_json("Dump Truck", "dump_truck", 2, seats=seats, construction=construction,
                                      wheel_parts=wheel_parts(wheels), weight_type="car", max_speed=0.6,
                                      turn_speed=2.5, max_health=100.0, inventory_size=27))


def mixer_truck():
    m = Mesh()
    wheels = truck_base(m, 6)
    drum_rear, drum_front = (0.0, 2.75, -2.6), (0.0, 2.0, 1.1)
    m.prism("$drum", drum_rear, drum_front, 0.95, 8, [5, 6])
    m.prism("$drum", add(drum_rear, (0, 0.05, -0.4)), drum_rear, 0.5, 8, [5, 6])
    m.box("body", -0.9, 1.1, -2.4, 0.9, 1.9, -2.1, 3)  # rear pedestal
    m.box("body", -0.9, 1.1, 0.9, 0.9, 1.7, 1.2, 3)    # front pedestal
    chute_pivot = (0.0, 1.6, -3.0)
    m.box("$chute", -0.2, 1.5, -3.2, 0.2, 1.7, -2.8, 3)
    tip = (0.0, 1.0, -4.6)
    m.beam("$chute_arm", chute_pivot, tip, 0.4, 0.15, 3)
    m.write(os.path.join(OBJ_DIR, "mixer_truck.obj"), "Concrete mixer truck")
    axis = norm(sub(drum_front, drum_rear))
    center = mul(add(drum_rear, drum_front), 0.5)
    joints = [
        joint("$drum", center, (0.0, round(axis[1], 4), round(axis[2], 4)), mode="spin", channel="drum"),
        joint("$chute", chute_pivot, (0, 1, 0), channel="chute_swing"),
        joint("$chute_arm", chute_pivot, (1, 0, 0), channel="chute_tilt", parent="$chute"),
    ]
    construction = {
        "machine": "mixer_truck",
        "joints": joints,
        "work_points": {"chute_tip": point("$chute_arm", add(tip, (0, -0.1, -0.1)))},
        "mixer_truck": {
            "chute_swing_speed": 3.0, "chute_swing_limit": 100.0, "chute_tilt_min": -25.0, "chute_tilt_max": 30.0,
            "chute_tilt_speed": 1.0, "pour_interval": 4, "spread_radius": 4,
            "capacity": 64, "water_capacity": 64, "water_per_bucket": 8, "mix_interval": 5,
            "drum_mix_speed": 6.0, "drum_pour_speed": -12.0, "drum_idle_speed": 1.5
        }
    }
    seats = [{"name": "driver", "offset_x": -0.6, "offset_y": 1.2, "offset_z": 2.4, "driver": True}]
    save_json("mixer_truck", base_json("Concrete Mixer Truck", "mixer_truck", 3, seats=seats,
                                       construction=construction, wheel_parts=wheel_parts(wheels),
                                       weight_type="car", max_speed=0.55, turn_speed=2.5, max_health=100.0,
                                       inventory_size=18))


# ---------------------------------------------------------------- tractor + trailer
HITCH = (0.0, 1.1, -1.2)
KINGPIN = (0.0, 1.1, 4.6)


def tractor():
    m = Mesh()
    m.box("body", -0.6, 0.5, -2.8, 0.6, 1.0, 3.2, 1)
    cab(m, "body", -1.2, 1.2, 1.0, 3.2, 1.2, 3.2, 9)
    m.box("body", -0.75, 1.0, -1.8, 0.75, 1.1, -0.6, 3)  # fifth wheel
    wheels = []
    for i, (x, z, steer) in enumerate([(1.1, 2.2, 30.0), (-1.1, 2.2, 30.0), (1.1, -0.9, 0), (-1.1, -0.9, 0),
                                       (1.1, -2.1, 0), (-1.1, -2.1, 0)]):
        part = "$wheel%d" % i
        m.wheel(part, x, 0.55, z, 0.55, 0.45)
        wheels.append((part, x, 0.55, z, steer))
    m.write(os.path.join(OBJ_DIR, "tractor_head.obj"), "Tractor head")
    construction = {"machine": "tractor",
                    "tractor": {"hitch_x": HITCH[0], "hitch_y": HITCH[1], "hitch_z": HITCH[2],
                                "couple_radius": 2.0, "towing_speed_factor": 0.7}}
    seats = [{"name": "driver", "offset_x": -0.6, "offset_y": 1.3, "offset_z": 2.2, "driver": True}]
    save_json("tractor_head", base_json("Tractor Head", "tractor_head", 3, seats=seats, construction=construction,
                                        wheel_parts=wheel_parts(wheels), weight_type="car", max_speed=0.7,
                                        turn_speed=2.5, max_health=100.0))


def lowboy_trailer():
    m = Mesh()
    m.box("body", -1.3, 1.1, 3.2, 1.3, 1.4, 5.4, 1)          # gooseneck
    m.box("body", -0.3, 0.9, 4.4, 0.3, 1.1, 4.8, 3)          # kingpin plate
    m.beam("body", (0, 1.25, 3.3), (0, 0.5, 2.4), 2.6, 0.25, 1)
    m.box("body", -1.5, 0.35, -4.2, 1.5, 0.6, 2.6, 3)         # low deck
    m.box("body", -1.5, 0.3, -4.3, 1.5, 0.6, -4.2, 9)
    wheels = []
    for i, (x, z) in enumerate([(1.2, -3.0), (-1.2, -3.0), (1.2, -3.9), (-1.2, -3.9)]):
        part = "$wheel%d" % i
        m.wheel(part, x, 0.45, z, 0.45, 0.45)
        wheels.append((part, x, 0.45, z, 0))
    for side, x0, x1 in (("l", 0.4, 1.4), ("r", -1.4, -0.4)):
        m.box("$hatch_ramp_" + side, x0, 0.6, -4.33, x1, 2.2, -4.23, 3)
    m.write(os.path.join(OBJ_DIR, "lowboy_trailer.obj"), "Low-loader trailer")
    toggle = [{"part": "$hatch_ramp_" + s, "pivot_x": 0.0, "pivot_y": 0.6, "pivot_z": -4.28, "mode": "rotate",
               "axis_x": -1.0, "axis_y": 0.0, "axis_z": 0.0, "max_angle": 110.0, "trigger": "key", "speed": 2.0}
              for s in ("l", "r")]
    runways = [
        {"width": 3.0, "center_x": 0.0, "height_y": 0.6, "start_z": -4.2, "end_z": 2.6},
        {"width": 3.0, "center_x": 0.0, "height_y": 0.3, "start_z": -5.8, "end_z": -4.2, "hatch_gated": True},
    ]
    construction = {"machine": "trailer",
                    "trailer": {"kingpin_x": KINGPIN[0], "kingpin_y": KINGPIN[1], "kingpin_z": KINGPIN[2],
                                "axle_z": -3.45, "max_articulation": 75.0}}
    save_json("lowboy_trailer", base_json("Low-loader Trailer", "lowboy_trailer", 3, seats=[],
                                          construction=construction, wheel_parts=wheel_parts(wheels),
                                          toggle_parts=toggle, runways=runways, weight_type="unknown",
                                          max_speed=0.7, max_health=120.0, fuel_consumption=-1.0, inventory_size=0))


if __name__ == "__main__":
    write_texture()
    backhoe()
    crane()
    bulldozer()
    pile_drivers()
    dump_truck()
    mixer_truck()
    tractor()
    lowboy_trailer()
    print("done")
