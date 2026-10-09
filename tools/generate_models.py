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
    def __init__(self, k=1.0):
        self.groups = {}  # name -> list of (verts[4 or n], color)
        self.order = []
        self.k = k        # uniform scale applied to everything (a smaller copy of the same design)

    def face(self, group, verts, color):
        if group not in self.groups:
            self.groups[group] = []
            self.order.append(group)
        self.groups[group].append(([mul(v, self.k) for v in verts], color))

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
GEOMETRY_KEYS = {"pivot_x", "pivot_y", "pivot_z", "x", "y", "z", "link_spacing", "offset_x", "offset_y", "offset_z"}


def scaled(data, k):
    """The JSON of a design built with Mesh(k): every model-space length times k (rotations per block
    divided by k). Settings in blocks (dig radius, speeds...) are left as they are."""
    if isinstance(data, list):
        return [scaled(v, k) for v in data]
    if not isinstance(data, dict):
        return data
    out = {}
    for key, v in data.items():
        if key in GEOMETRY_KEYS and isinstance(v, (int, float)) and not isinstance(v, bool):
            out[key] = round(v * k, 4)
        elif key == "rotations_per_block":
            out[key] = round(v / k, 4)
        else:
            out[key] = scaled(v, k)
    return out


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


def rotate(v, axis, deg):
    """Right-handed rotation of v about a unit axis."""
    a = math.radians(deg)
    c, s_ = math.cos(a), math.sin(a)
    k = norm(axis)
    d = sum(k[i] * v[i] for i in range(3))
    kv = cross(k, v)
    return tuple(v[i] * c + kv[i] * s_ + k[i] * d * (1 - c) for i in range(3))


def length(v):
    return math.sqrt(v[0] ** 2 + v[1] ** 2 + v[2] ** 2)


def ram(m, name, parent, base, attach, child, pivot, axis, lo, hi, size=0.14, factor=1.0, offset=0.0):
    """A hydraulic ram from `base` (fixed in `parent`) to `attach` (a point on `child`, which turns
    about pivot/axis by channel * factor + offset degrees for channel lo..hi; modelled at angle 0).
    Barrel $name and rod $name_s1 - or more nested stages when one rod cannot cover the stroke.
    The joints use the addon's "aim" (barrel points at the attachment) and "reach" (each stage takes
    an equal share of the growth in length) modes, so the ram follows the child exactly in every pose.
    Returns the joints."""
    u = norm(sub(attach, base))
    rest = length(sub(attach, base))
    lengths = [length(sub(add(pivot, rotate(sub(attach, pivot), axis, (lo + (hi - lo) * i / 24) * factor + offset)),
                          base)) for i in range(25)] + [rest]
    lmin, lmax = min(lengths), max(lengths)
    section = lmin * 0.92
    stages = max(2, int(math.ceil((lmax - section) / (section * 0.82))) + 1)
    parts = ["$" + name] + ["$%s_s%d" % (name, i) for i in range(1, stages)]
    if stages == 2 and lmax <= 1.75 * lmin:
        barrel = min(rest * 0.62, lmin * 0.9)
        m.beam(parts[0], sub(base, mul(u, size * 0.5)), add(base, mul(u, barrel)), size, size, 1)
        m.beam(parts[1], add(base, mul(u, max(rest - lmin * 0.95, size))), attach, size * 0.55, size * 0.55, 10)
    else:
        step = (rest - section) / (stages - 1)
        for i, part in enumerate(parts):
            a0 = add(base, mul(u, step * i))
            w = size * (1.0 - 0.18 * i)
            m.beam(part, a0, add(a0, mul(u, section)), w, w, 1 if i == 0 else 10)
    target = point(child, attach)
    anchor = point(parts[0], base)
    joints = [joint(parts[0], base, axis, mode="aim", parent=parent, target=target)]
    for i in range(1, stages):
        joints.append(joint(parts[i], (0, 0, 0), tuple(round(v, 4) for v in u), mode="reach", parent=parts[i - 1],
                            target=target, anchor=anchor, factor=round(1.0 / (stages - 1), 4)))
    return joints


# ---------------------------------------------------------------- crawlers
def crawler_layout(z_half, height, r_end, wheel_r, n_wheels, carrier_r=0.0, n_carrier=0, shoe_t=0.07):
    """Side-view (y, z) layout of one crawler, the way tank running gear is laid out so nothing
    overlaps: idler (front) and sprocket (rear) of radius r_end with their tops touching the inside of
    the top run; road wheels on the bottom run, clear of the end wheels and of each other; carrier
    rollers under the top run; and the belt path (centre line of the shoes) in the base mod's order:
    front-bottom, up round the idler, back along the top, down round the sprocket."""
    t = shoe_t
    R = r_end + t / 2
    zc = z_half - r_end - t
    yc = height - t - r_end
    gap = 0.04
    dy = yc - (t + wheel_r)
    need = r_end + wheel_r + gap
    dz = math.sqrt(max(need * need - dy * dy, 0.0)) if need > abs(dy) else 0.0
    z_lo, z_hi = -zc + dz, zc - dz
    n = max(2, n_wheels)
    while n > 2 and (z_hi - z_lo) / (n - 1) < 2 * wheel_r + 0.03:
        n -= 1
    wheels = [(t + wheel_r, z_lo + (z_hi - z_lo) * i / (n - 1)) for i in range(n)]
    carriers = []
    if n_carrier:
        lim = (zc - (r_end + carrier_r + 0.08)) * 0.55
        zs = [0.0] if n_carrier == 1 else [-lim + 2 * lim * i / (n_carrier - 1) for i in range(n_carrier)]
        carriers = [(height - t - carrier_r, z) for z in zs]
    touching = (yc - r_end) - t < 0.03
    a0 = -90.0 if touching else -45.0
    arc = lambda zz, a1, a2: [(yc + R * math.sin(math.radians(a1 + (a2 - a1) * i / 6)),
                               zz + R * math.cos(math.radians(a1 + (a2 - a1) * i / 6))) for i in range(7)]
    path = []
    if not touching:
        path.append((t / 2, z_hi + wheel_r * 0.5))
    path += arc(zc, a0, 90.0) + arc(-zc, 90.0, 180.0 - a0)
    if not touching:
        path.append((t / 2, z_lo - wheel_r * 0.5))
    return dict(t=t, zc=zc, yc=yc, wheels=wheels, carriers=carriers, path=path)


def path_placements(path, spacing, count):
    """The base mod's placement rule: (y, z, angle degrees) of link i at belt travel 0."""
    n = len(path)
    cum = [0.0]
    for i in range(n):
        a, b = path[i], path[(i + 1) % n]
        cum.append(cum[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    out = []
    for i in range(count):
        d = (i * spacing) % cum[-1]
        k = 0
        while k < n - 1 and cum[k + 1] < d:
            k += 1
        a, b = path[k], path[(k + 1) % n]
        f = (d - cum[k]) / max(cum[k + 1] - cum[k], 1e-9)
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f,
                    math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))))
    return out


def track_link(m, group, x_in, x_out, spacing, t, place=None):
    """One shoe with a grouser: in the base mod's link frame (local +Y outward, centred on y = z = 0), or
    moved to a placement (y, z, angle) for links this addon draws itself."""
    x0, x1 = min(x_in, x_out), max(x_in, x_out)
    xc, w = (x0 + x1) / 2, x1 - x0
    shells = [((xc, 0.0, 0.0), w / 2, t / 2, spacing * 0.45, 1),
              ((xc, t / 2 + 0.015, 0.0), w * 0.48, 0.015, 0.025, 1)]
    for c, hx, hy, hz, color in shells:
        ax, ay, az = (1, 0, 0), (0, 1, 0), (0, 0, 1)
        if place is not None:
            y, z, ang = place
            ay, az = rotate(ay, (1, 0, 0), ang + 90.0), rotate(az, (1, 0, 0), ang + 90.0)
            c = add(rotate(c, (1, 0, 0), ang + 90.0), (0, y, z))
        m.oriented_box(group, c, ax, ay, az, hx, hy, hz, color)


def crawler(m, side, x_in, x_out, z_half, height, r_end, wheel_r, n_wheels, carrier_r=0.0, n_carrier=0,
            link_len=0.2, shoe_t=0.07, frame_group="body", addon_parent=None):
    """A complete crawler for one side ("l" / "r"): frame, idler, sprocket, road wheels, carrier rollers
    and shoes, laid out by crawler_layout(). Returns its JSON:
      - normally the base mod draws and drives it: {"crawler_tracks": [...], "track_roller_parts": [...]};
      - with addon_parent (a crawler that moves, e.g. one that widens) everything is drawn by this addon on
        top of that part: {"crawler_tracks": [belt only], "joints": [...], "crawlers": [...]}."""
    L = crawler_layout(z_half, height, r_end, wheel_r, n_wheels, carrier_r, n_carrier, shoe_t)
    s = 1 if x_in > 0 else -1
    xc = (x_in + x_out) / 2
    t, zc, yc, path = L["t"], L["zc"], L["yc"], L["path"]
    total = sum(math.hypot(path[(i + 1) % len(path)][0] - path[i][0], path[(i + 1) % len(path)][1] - path[i][1])
                for i in range(len(path)))
    count = max(1, round(total / link_len))
    spacing = total / count
    frame = addon_parent or frame_group
    # track frame between the road wheels and the carrier rollers / top run
    fy0 = t + 2 * wheel_r + 0.02
    fy1 = (height - t - 2 * carrier_r - 0.02) if n_carrier else yc + r_end * 0.3
    fz = zc - r_end - 0.03
    m.box(frame, x_in + s * 0.08, fy0, -fz, x_out - s * 0.08, fy1, fz, 1)
    for zz in (zc, -zc):   # yokes holding the end wheels
        m.box(frame, x_in + s * 0.08, yc - 0.06, min(zz, -fz if zz < 0 else fz), x_in + s * 0.16, yc + 0.06,
              max(zz, -fz if zz < 0 else fz), 1)
    rollers = []
    for name, z, sides in (("sprocket", -zc, 10), ("idler", zc, 10)):
        g = "$%s_%s" % (name, side)
        m.prism(g, (x_in + s * 0.03, yc, z), (x_out - s * 0.03, yc, z), r_end * 0.97, sides, [3, 1])
        m.box(g, x_out - s * 0.035, yc + r_end * 0.3, z - 0.04, x_out - s * 0.01, yc + r_end * 0.8, z + 0.04, 0)
        rollers.append((g, yc, z, r_end))
    for i, (y, z) in enumerate(L["wheels"]):
        g = "$wheel%d_%s" % (i, side)
        m.prism(g, (x_in + s * 0.07, y, z), (x_out - s * 0.07, y, z), wheel_r, 8, [3, 10])
        m.box(g, x_out - s * 0.075, y + wheel_r * 0.3, z - 0.02, x_out - s * 0.05, y + wheel_r * 0.85, z + 0.02, 0)
        rollers.append((g, y, z, wheel_r))
    for i, (y, z) in enumerate(L["carriers"]):
        g = "$carrier%d_%s" % (i, side)
        m.prism(g, (xc - s * 0.12, y, z), (xc + s * 0.12, y, z), carrier_r, 8, [3, 10])
        m.prism(frame, (x_in + s * 0.04, y, z), (xc - s * 0.1, y, z), carrier_r * 0.4, 6, [1])
        rollers.append((g, y, z, carrier_r))
    belt = {"part": "$crawler_" + side, "x": round(xc, 4), "link_spacing": round(spacing, 5),
            "path": [{"y": round(y, 4), "z": round(z, 4)} for y, z in path]}
    if addon_parent is None:
        track_link(m, belt["part"], x_in, x_out, spacing, t)
        return {"crawler_tracks": [belt],
                "track_roller_parts": [{"part": g, "pivot_x": round(xc, 4), "pivot_y": round(y, 4),
                                        "pivot_z": round(z, 4), "rotations_per_block": round(1 / (2 * math.pi * r), 4)}
                                       for g, y, z, r in rollers]}
    # drawn by this addon: one group per link at its travel-0 place; the base mod only measures the belt
    # travel of a geometry-less dummy track with the same x (so turning still drives each side apart)
    belt["part"] = "$belt_" + side
    prefix = "$link_%s_" % side
    for i, place in enumerate(path_placements(path, spacing, count)):
        track_link(m, prefix + str(i), x_in, x_out, spacing, t, place=place)
    joints = [joint(g, (0, y, z), (1, 0, 0), mode="belt", parent=addon_parent, belt=belt["part"],
                    factor=round(-360.0 / (2 * math.pi * r), 3)) for g, y, z, r in rollers]
    crawlers = [{"belt": belt["part"], "parent": addon_parent, "link_prefix": prefix,
                 "link_spacing": belt["link_spacing"], "path": belt["path"]}]
    return {"crawler_tracks": [belt], "joints": joints, "crawlers": crawlers}


def crawlers(m, x_in, x_out, z_half, height, frame_group="body", parents=None, **kw):
    """Both sides; returns (top-level JSON entries, extra joints, addon crawlers)."""
    top, joints, extra = {"crawler_tracks": [], "track_roller_parts": []}, [], []
    for side, s in (("l", 1), ("r", -1)):
        r = crawler(m, side, s * x_in, s * x_out, z_half, height, frame_group=frame_group,
                    addon_parent=(parents or {}).get(side), **kw)
        top["crawler_tracks"] += r["crawler_tracks"]
        top["track_roller_parts"] += r.get("track_roller_parts", [])
        joints += r.get("joints", [])
        extra += r.get("crawlers", [])
    if not top["track_roller_parts"]:
        del top["track_roller_parts"]
    return top, joints, extra


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
BACKHOE_RANGES = {"boom": (-40.0, 35.0), "arm": (-50.0, 110.0)}


def backhoe_model(k, model):
    """The backhoe design, built at scale k (1.0 = the full-size machine; the mini backhoe is the same
    design at 0.6, built small rather than scaled at run time so its crawlers grip the ground)."""
    m = Mesh(k)
    top, _, _ = crawlers(m, 0.72, 1.45, 2.05, 0.78, r_end=0.3, wheel_r=0.12, n_wheels=6, carrier_r=0.08,
                         n_carrier=1, link_len=0.2)
    m.box("body", -0.72, 0.25, -1.2, 0.72, 0.72, 1.2, 3)
    swing_pivot = (0.0, 0.8, 0.0)
    m.box("$upper", -1.3, 0.8, -1.9, 1.3, 1.0, 1.4, 0)
    m.box("$upper", -1.3, 1.0, -1.9, 0.2, 1.9, 0.3, 0)
    m.box("$upper", -1.3, 0.9, -2.25, 1.3, 1.7, -1.9, 1)
    cab(m, "$upper", 0.3, 1.25, 1.0, 2.5, 0.1, 1.35)
    B = (-0.2, 1.3, 1.1)
    A = (-0.2, 3.5, 3.4)
    K = (-0.2, 1.4, 4.4)
    T = (-0.2, 0.6, 4.95)
    knee = (-0.2, 2.95, 1.65)   # the boom rises steeply from its foot, then runs forward to the tip
    m.box("$upper", -0.55, 1.0, 0.8, 0.15, 1.6, 1.4, 1)  # boom foot
    m.box("$upper", -0.4, 0.95, 1.4, 0.0, 1.25, 1.75, 1)  # boom ram bracket
    m.beam("$boom", B, knee, 0.45, 0.55, 0)
    m.beam("$boom", knee, A, 0.45, 0.5, 0)
    m.box("$boom", -0.425, 2.7, 1.4, 0.025, 3.2, 1.9, 0)  # knee
    m.box("$boom", -0.35, 2.3, 1.7, -0.05, 2.6, 2.0, 1)  # boom ram lug (under the knee)
    m.box("$boom", -0.35, 3.15, 1.35, -0.05, 3.45, 1.65, 1)  # arm ram lug (on top of the knee)
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
    # hydraulic rams: boom (upper -> under the knee) and arm (top of the knee -> arm heel)
    rams = ram(m, "boom_ram", "$upper", (-0.2, 1.1, 1.6), (-0.2, 2.45, 1.85), "$boom", B, (-1, 0, 0),
               *BACKHOE_RANGES["boom"], size=0.18)
    rams += ram(m, "arm_ram", "$boom", (-0.2, 3.3, 1.5), add(A, (0, 0.45, -0.45)), "$arm", A, (-1, 0, 0),
                *BACKHOE_RANGES["arm"], size=0.16)
    m.write(os.path.join(OBJ_DIR, model + ".obj"), "Backhoe")

    joints = [
        joint("$upper", swing_pivot, (0, 1, 0), channel="swing"),
        joint("$boom", B, (-1, 0, 0), channel="boom", parent="$upper"),
        joint("$arm", A, (-1, 0, 0), channel="arm", parent="$boom"),
        joint("$bucket", K, (1, 0, 0), channel="bucket", parent="$arm"),
        joint("$bucket_load", load_pivot, (0, 1, 0), mode="scale", channel="load", parent="$bucket", offset=0.0),
    ] + rams
    construction = {
        "machine": "excavator",
        "joints": joints,
        "work_points": {"bucket_tip": point("$bucket", T)},
        "seat_parts": [{"seat": 0, "part": "$upper"}],
    }
    seats = [{"name": "operator", "offset_x": 0.78, "offset_y": 0.95, "offset_z": 0.7, "driver": True}]
    return scaled(dict(top, construction=construction, seats=seats), k)


def backhoe():
    settings = {
        "swing_speed": 2.5, "boom_speed": 1.5, "arm_speed": 2.0,
        "boom_min": BACKHOE_RANGES["boom"][0], "boom_max": BACKHOE_RANGES["boom"][1],
        "arm_min": BACKHOE_RANGES["arm"][0], "arm_max": BACKHOE_RANGES["arm"][1],
        "bucket_min": -60.0, "bucket_max": 110.0, "curl_speed": 4.0,
        "capacity": 4, "dig_radius": 0.8, "base_dig_ticks": 4.0, "dump_angle": -20.0, "dump_interval": 3,
        "dump_spread_radius": 4, "dump_slope": 0.75,
        "resistance": {"hardness_scale": 1.0, "refusal_hardness": 3.5}
    }
    full = backhoe_model(1.0, "backhoe")
    full["construction"]["excavator"] = settings
    save_json("backhoe", base_json("Backhoe", "backhoe", 2, max_speed=0.3, turn_speed=2.5, max_health=140.0,
                                   **full))

    mini = backhoe_model(0.6, "mini_backhoe")
    mini["construction"]["excavator"] = dict(settings, capacity=2)
    save_json("mini_backhoe", base_json("Mini Backhoe", "mini_backhoe", 1, width=1.8, height=1.8, max_speed=0.28,
                                        turn_speed=3.0, max_health=70.0, step_height=0.6, max_fuel=600.0,
                                        **mini))


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
        "swing_sound": dict(SWING_ALARM),
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
    top, _, _ = crawlers(m, 0.78, 1.5, 1.95, 0.85, r_end=0.32, wheel_r=0.13, n_wheels=6, carrier_r=0.09,
                         n_carrier=1, link_len=0.2)
    m.box("body", -0.78, 0.4, -1.6, 0.78, 1.6, 1.5, 0)
    m.box("body", -0.6, 1.6, 0.2, 0.6, 1.75, 1.5, 1)
    cab(m, "body", -0.7, 0.7, 1.6, 2.8, -1.4, -0.2)
    m.box("body", 0.45, 1.6, 0.6, 0.6, 2.4, 0.75, 1)  # exhaust
    for sx in (1.5, -1.5):                             # push-arm trunnions on the track frames
        m.box("body", sx - 0.02 * (1 if sx > 0 else -1), 0.38, -0.1, sx + 0.08 * (1 if sx > 0 else -1), 0.62,
              0.3, 3)
    m.box("$blade", -1.6, 0.0, 2.1, 1.6, 1.2, 2.3, 0)
    m.box("$blade", -1.6, 0.0, 2.25, 1.6, 0.12, 2.4, 3)  # cutting edge
    for sx in (1.6, -1.6):                             # push arms, outside the crawlers
        m.beam("$blade", (sx, 0.5, 0.1), (sx, 0.5, 2.1), 0.14, 0.2, 1)
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
                                     max_speed=0.3, turn_speed=2.5, max_health=160.0, **top))


# ---------------------------------------------------------------- pile drivers
MAST_PIVOT = (0.0, 1.0, 1.6)
PILE_POINT = (0.0, 1.0, 2.35)
SMALL_SWING_PIVOT = (0.0, 0.7, 0.0)
SWING_ALARM = {"sound": "minecraft:block.note_block.bit", "volume": 0.6, "pitch": 1.8, "interval": 8}


def jacks(m, x, z_inner, z_outer, y_beam, pad_half):
    """Front ($jack_f, slides +Z) and rear ($jack_b, slides -Z) jack beams with pads, at +-x."""
    for sx in (x, -x):
        m.box("$jack_f", sx - 0.12, y_beam, z_inner, sx + 0.12, y_beam + 0.25, z_outer, 3)
        m.box("$jack_f", sx - pad_half, 0.0, z_outer - 0.1, sx + pad_half, y_beam + 0.25, z_outer + 0.3, 1)
        m.box("$jack_b", sx - 0.12, y_beam, -z_outer, sx + 0.12, y_beam + 0.25, -z_inner, 3)
        m.box("$jack_b", sx - pad_half, 0.0, -z_outer - 0.3, sx + pad_half, y_beam + 0.25, -z_outer + 0.1, 1)


def jack_joints(travel):
    return [
        joint("$jack_f", (0, 0, 0), (0, 0, 1), mode="slide", channel="outrigger", factor=travel),
        joint("$jack_b", (0, 0, 0), (0, 0, -1), mode="slide", channel="outrigger", factor=travel),
    ]


def pile_driver_base(m):
    """Small pile driver: the leader is pinned at its foot and held by two backstays; for travel it is
    laid back onto a rest over the upper structure. Returns (crawler JSON, backstay joints)."""
    top, _, _ = crawlers(m, 0.72, 1.45, 2.05, 0.68, r_end=0.27, wheel_r=0.11, n_wheels=6, carrier_r=0.07,
                         n_carrier=1, link_len=0.2)
    m.box("body", -0.72, 0.25, -1.3, 0.72, 0.68, 1.3, 3)
    jacks(m, 0.55, 1.2, 2.0, 0.3, 0.17)   # between the crawlers
    m.box("$upper", -0.95, 0.7, -2.0, 0.95, 1.0, 1.3, 0)
    m.box("$upper", -0.9, 1.0, -1.8, -0.3, 1.7, 0.9, 0)    # engine hood (right, clear of the leader)
    m.box("$upper", -0.95, 0.8, -2.25, 0.95, 1.5, -1.9, 1)
    cab(m, "$upper", 0.25, 0.95, 1.0, 2.4, 0.0, 1.25)
    m.box("$upper", -0.25, 1.0, -1.75, 0.25, 1.38, -1.45, 5)  # rest for the lowered leader
    m.box("$upper", -0.35, 0.7, 1.2, 0.35, 1.2, 1.7, 3)  # mast foot
    m.box("$mast", -0.2, 1.0, 1.6, 0.2, 8.5, 1.9, 5)
    m.box("$mast", -0.35, 8.3, 1.5, 0.35, 8.6, 2.6, 3)  # crown
    m.box("$mast", -0.25, 1.0, 1.9, 0.25, 1.3, 2.1, 3)  # lower guide
    stays = []
    for side, sx in (("l", 1), ("r", -1)):
        m.box("$upper", sx * 0.2, 1.0, -0.55, sx * 0.36, 1.35, -0.25, 3)
        m.box("$mast", sx * 0.2, 4.85, 1.5, sx * 0.36, 5.15, 1.62, 3)   # backstay lug
        # held at about half the leader height (three-point support)
        stays += ram(m, "backstay_" + side, "$upper", (sx * 0.28, 1.3, -0.4), (sx * 0.28, 5.0, 1.6), "$mast",
                     MAST_PIVOT, (1, 0, 0), 0.0, 1.0, 0.1, factor=80.0, offset=-80.0)
    return top, stays


def pile_driver_json(name, model, tier, joints_list, work_point, pile_settings, crawlers=None, **kw):
    construction = {
        "machine": "pile_driver",
        "joints": joints_list,
        **({"crawlers": crawlers} if crawlers else {}),
        "work_points": {"pile_point": work_point},
        "seat_parts": [{"seat": 0, "part": "$upper"}],
        "swing_sound": dict(SWING_ALARM),
        "pile_driver": pile_settings,
    }
    seats = [kw.pop("seat")]
    args = dict(seats=seats, construction=construction, max_speed=0.25, turn_speed=2.0, max_health=140.0,
                inventory_size=18)
    args.update(kw)
    save_json(model, base_json(name, model, tier, **args))


SMALL_SETUP = {"swing_speed": 1.5, "swing_limit": 0.0, "outrigger_speed": 0.04, "require_outriggers": True,
               "crawler_extension": False}


def small_joints(extra):
    return ([joint("$upper", SMALL_SWING_PIVOT, (0, 1, 0), channel="swing"),
             joint("$mast", MAST_PIVOT, (1, 0, 0), channel="mast", factor=80.0, offset=-80.0, parent="$upper")]
            + jack_joints(0.6) + extra)


def pile_drivers():
    small_seat = {"name": "operator", "offset_x": 0.6, "offset_y": 1.0, "offset_z": 0.6, "driver": True}

    m = Mesh()
    top, stays = pile_driver_base(m)
    # Steel pipe pile under the hammer. "feed" is how far the current block has been driven (0..1, the
    # module counts blows), so the pile sinks one block and the next one takes its place; the hammer
    # rides on the pile and is lifted off it by "hammer".
    cz = PILE_POINT[2]
    m.prism("$pile", (0, 0.0, cz), (0, 1.55, cz), 0.2, 8, [3, 10])
    m.prism("$pile", (0, 1.55, cz), (0, 1.7, cz), 0.26, 8, [1])          # drive cap
    m.box("$hammer", -0.3, 1.7, cz - 0.3, 0.3, 2.7, cz + 0.3, 1)
    m.box("$hammer", -0.05, 2.7, cz - 0.05, 0.05, 6.2, cz + 0.05, 3)    # winch rope (rides with the hammer)
    m.write(os.path.join(OBJ_DIR, "pile_driver_hammer.obj"), "Drop hammer pile driver")
    pile_driver_json("Pile Driver (Drop Hammer)", "pile_driver_hammer", 3,
                     small_joints([joint("$pile", (0, 0, 0), (0, 1, 0), mode="slide", channel="feed", factor=-1.0,
                                         parent="$mast"),
                                   joint("$hammer", (0, 0, 0), (0, 1, 0), mode="slide", channel="hammer",
                                         parent="$pile")] + stays),
                     point("$mast", PILE_POINT),
                     dict(SMALL_SETUP, **{"mode": "hammer", "max_depth": 32, "default_depth": 8, "mast_speed": 0.02,
                                          "blows_per_block": 2.0, "max_blows_per_block": 40, "lift_ticks": 20,
                                          "drop_ticks": 5, "lift_height": 2.0,
                                          "resistance": {"hardness_scale": 1.0, "refusal_hardness": 20.0}}),
                     seat=dict(small_seat), **top)

    m = Mesh()
    top, stays = pile_driver_base(m)
    # rotary head high on the leader, gripping the top of the steel pipe pile (through the lower guide)
    m.box("$rotary_head", -0.4, 4.0, 1.95, 0.4, 4.8, 2.75, 3)
    m.box("$rotary_head", -0.3, 4.8, 2.05, 0.3, 5.2, 2.65, 1)
    pile(m, (0.0, 2.35), 0.0, 4.0, 0.2)
    m.write(os.path.join(OBJ_DIR, "pile_driver_rotary.obj"), "Rotary press-in pile driver")
    pile_driver_json("Pile Driver (Rotary)", "pile_driver_rotary", 4,
                     small_joints([joint("$rotary_head", (0, 0, 0), (0, 1, 0), mode="slide", channel="feed",
                                         factor=-1.0, parent="$mast"),
                                   joint("$pile", (0, 0, 2.35), (0, 1, 0), mode="spin", channel="rpm",
                                         factor=0.3, parent="$rotary_head")] + stays),
                     point("$mast", PILE_POINT),
                     dict(SMALL_SETUP, **{"mode": "rotary", "max_depth": 40, "default_depth": 12, "mast_speed": 0.02,
                                          "ticks_per_block": 40.0, "rpm": 30.0, "min_rpm": 5.0, "rpm_response": 0.15,
                                          "resistance": {"hardness_scale": 1.0, "refusal_hardness": 25.0}}),
                     seat=dict(small_seat), **top)

    large_pile_driver()


def pile(m, center_xz, y0, y1, radius):
    """Steel pipe pile for rotary press-in: plain tube, pointed shoe and a tip wing ($pile spins)."""
    cx, cz = center_xz
    m.prism("$pile", (cx, y0 + 0.2, cz), (cx, y1, cz), radius, 8, [3, 10])
    m.prism("$pile", (cx, y0, cz), (cx, y0 + 0.2, cz), radius * 0.5, 8, [3])
    for i in range(8):                       # tip wing: about one turn of blade around the shoe
        a = i * 2 * math.pi / 7
        y = y0 + 0.2 + i * 0.06
        ox, oz = math.cos(a) * radius * 1.5, math.sin(a) * radius * 1.5
        m.box("$pile", cx + ox - 0.12, y, cz + oz - 0.12, cx + ox + 0.12, y + 0.05, cz + oz + 0.12, 3)


# Large pile driver geometry
L_SWING_PIVOT = (0.0, 1.05, 0.0)
L_FOOT = (0.0, 1.5, 2.7)      # leader foot pin
L_DOWN = 78.0                 # the leader lowers back by this angle
L_PILE_Z = 3.5
L_PILE_POINT = (0.0, 1.2, L_PILE_Z)
TRACK_EXT = 0.7               # how far each crawler slides out when widened


def large_pile_driver():
    """Large rotary pile driver:
    - the crawlers widen sideways ("track_width"); since they move, this addon draws them itself
      (links, idler, sprocket, road wheels and carrier rollers ride on $track_l / $track_r and follow the
      belt travel the base mod measures for two geometry-less tracks, $belt_l / $belt_r);
    - four jacks between the crawlers ("outrigger");
    - the leader is pinned at its foot and held by two backstays (three-point support); "mast"
      raises it or lays it back onto the rest; the backstays hold it at about half its height; the
      rotary head sits high on the leader and turns / presses in a steel pipe pile through the guide.
    """
    m = Mesh()
    m.box("body", -1.0, 0.45, -2.4, 1.0, 1.0, 2.4, 3)
    jacks(m, 0.68, 2.2, 3.1, 0.5, 0.24)
    joints_list = []
    for side, sign in (("l", 1), ("r", -1)):
        g = "$track_" + side
        m.box(g, sign * 0.95, 0.5, -1.6, sign * 1.1, 0.9, 1.6, 3)   # slide beam into the car body
        joints_list.append(joint(g, (0, 0, 0), (sign, 0, 0), mode="slide", channel="track_width", factor=TRACK_EXT))
    top, belt_joints, addon_crawlers = crawlers(m, 1.1, 1.95, 3.05, 1.0, parents={"l": "$track_l", "r": "$track_r"},
                                                r_end=0.42, wheel_r=0.16, n_wheels=7, carrier_r=0.11, n_carrier=2,
                                                link_len=0.25, shoe_t=0.08)
    joints_list += belt_joints

    # Upper structure (engine hood on the right, clear of the lowered leader)
    m.box("$upper", -1.6, 1.05, -3.2, 1.6, 1.4, 1.8, 0)   # (the crawlers' top runs end at y 1.0)
    m.box("$upper", -1.5, 1.4, -3.0, -0.45, 2.5, 0.2, 0)
    m.box("$upper", -1.6, 1.2, -3.6, 1.6, 2.3, -3.2, 1)
    cab(m, "$upper", 0.45, 1.45, 1.4, 3.0, 0.3, 1.7)
    m.box("$upper", -0.5, 1.05, 1.8, 0.5, 1.8, 2.6, 3)            # leader bracket
    rest = (-2.75, 1.5 + (L_FOOT[2] + 2.75) * math.tan(math.radians(90 - L_DOWN)) - 0.4)
    m.box("$upper", -0.35, 1.4, rest[0] - 0.15, 0.35, rest[1], rest[0] + 0.15, 3)  # rest for the lowered leader

    # Leader: pinned at its foot on the bracket and held by two backstays (three-point support).
    # Lowering it ("mast" 0) lays it back over the upper structure onto the rest.
    m.box("$leader", -0.35, 1.2, 2.4, 0.35, 14.5, 3.0, 5)
    for y in (4.5, 8.0, 11.5):
        m.box("$leader", -0.4, y - 0.06, 2.35, 0.4, y + 0.06, 3.05, 5)   # section flanges
    m.box("$leader", -0.5, 14.3, 2.2, 0.5, 14.8, 3.6, 3)    # crown sheaves
    m.box("$leader", -0.45, 1.2, 3.0, 0.45, 1.5, 4.0, 3)    # pile guide at the foot
    stays = []
    for side, sx in (("l", 1), ("r", -1)):
        m.box("$upper", sx * 0.45, 2.3, -3.45, sx * 0.85, 2.5, -3.15, 3)
        m.box("$leader", sx * 0.2, 7.8, 2.25, sx * 0.4, 8.2, 2.42, 3)   # backstay lug
        # held at about half the leader height (three-point support)
        stays += ram(m, "backstay_" + side, "$upper", (sx * 0.65, 2.4, -3.3), (sx * 0.3, 8.0, 2.4), "$leader",
                     L_FOOT, (1, 0, 0), 0.0, 1.0, 0.16, factor=L_DOWN, offset=-L_DOWN)
    # Rotary head high on the leader, gripping the top of the steel pipe pile (through the guide).
    m.box("$rotary_head", -0.6, 7.0, 3.0, 0.6, 8.2, 4.0, 3)
    m.box("$rotary_head", -0.45, 8.2, 3.1, 0.45, 8.8, 3.9, 1)
    m.box("$rotary_head", -0.2, 7.3, 2.9, 0.2, 7.9, 3.1, 1)
    pile(m, (0.0, L_PILE_Z), 0.0, 7.0, 0.3)
    m.write(os.path.join(OBJ_DIR, "pile_driver_large.obj"), "Large rotary pile driver")

    joints_list += jack_joints(0.8)
    joints_list += [
        joint("$upper", L_SWING_PIVOT, (0, 1, 0), channel="swing"),
        joint("$leader", L_FOOT, (1, 0, 0), channel="mast", factor=L_DOWN, offset=-L_DOWN, parent="$upper"),
        joint("$rotary_head", (0, 0, 0), (0, 1, 0), mode="slide", channel="feed", factor=-1.0,
              parent="$leader"),
        joint("$pile", (0, 0, L_PILE_Z), (0, 1, 0), mode="spin", channel="rpm", factor=0.3,
              parent="$rotary_head"),
    ] + stays
    pile_driver_json("Large Pile Driver (Rotary)", "pile_driver_large", 5, joints_list,
                     point("$leader", L_PILE_POINT),
                     {"mode": "rotary", "max_depth": 60, "default_depth": 16, "mast_speed": 0.012,
                      "swing_speed": 1.0, "outrigger_speed": 0.03, "require_outriggers": True,
                      "crawler_extension": True, "track_width_speed": 0.02,
                      "ticks_per_block": 30.0, "rpm": 25.0, "min_rpm": 4.0, "rpm_response": 0.1,
                      "resistance": {"hardness_scale": 0.8, "refusal_hardness": 30.0}},
                     seat={"name": "operator", "offset_x": 0.95, "offset_y": 1.4, "offset_z": 0.9, "driver": True},
                     max_speed=0.2, turn_speed=1.5, max_health=220.0, inventory_size=27,
                     crawlers=addon_crawlers, **top)


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
