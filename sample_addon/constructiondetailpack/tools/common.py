"""Shared helpers for the detailed model pack (built with Meshkit).

Conventions (tudursvehiclemod): 1 unit = 1 block = 1 m, +X = left, +Y = up, +Z = forward,
origin = centre of the vehicle on the ground. Moving parts start with "$".
"""
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MESHKIT = os.environ.get("MESHKIT_PATH", os.path.join(HERE, "..", "..", "Meshkit"))
sys.path.insert(0, os.path.abspath(MESHKIT))

import meshkit as mk  # noqa: E402
from meshkit import armor, mech  # noqa: E402,F401

# ------------------------------------------------------------------ colours (tag -> rgb)
PROJECTED = {
    "paint": (226, 168, 30),     # machine yellow
    "paint2": (64, 64, 62),      # dark grey (frames, counterweights)
    "cabin": (226, 168, 30),     # cab shell (recoloured per machine)
    "white": (226, 226, 220),
    "red": (172, 38, 34),
    "orange": (222, 108, 28),
    "stripe": (226, 168, 30),    # hazard stripes, painted later
    "glass": (96, 128, 146),
    "earth": (118, 84, 52),
}
FLAT = {
    "steel": (138, 142, 148),
    "chrome": (214, 216, 220),
    "black": (30, 30, 30),
    "track": (52, 50, 47),
    "tyre": (26, 26, 26),
    "rim": (176, 176, 170),
    "seat": (44, 44, 48),
    "lamp": (250, 238, 190),
    "tail": (200, 40, 30),
    "rope": (70, 70, 70),
    "concrete": (166, 166, 158),
    "grille": (40, 40, 40),
    "wood": (150, 112, 70),
}


def model(name):
    return mk.Model(name)


# ------------------------------------------------------------------ primitives
def box(m, part, x0, y0, z0, x1, y1, z1, tag="paint"):
    x0, x1 = sorted((x0, x1)); y0, y1 = sorted((y0, y1)); z0, z1 = sorted((z0, z1))
    m.add(part, mk.box((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, x1 - x0, y1 - y0, z1 - z0, tag=tag))


def cbox(m, part, x0, y0, z0, x1, y1, z1, tag="paint", c=0.05):
    x0, x1 = sorted((x0, x1)); y0, y1 = sorted((y0, y1)); z0, z1 = sorted((z0, z1))
    m.add(part, mech.cbox((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, x1 - x0, y1 - y0, z1 - z0, tag=tag, c=c))


def cyl(m, part, p0, p1, r, n=8, tag="steel", r1=None):
    m.add(part, mk.cylinder(p0, p1, r, r1, n=n, tag=tag))


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def bar(m, part, a, b, w, h, tag="paint", up=(0, 1, 0)):
    """Rectangular bar from a to b: w across (perpendicular to `up`), h along `up`."""
    a = np.asarray(a, float); b = np.asarray(b, float)
    d = unit(b - a)
    side = np.cross(up, d)
    if np.linalg.norm(side) < 1e-6:
        side = np.array([1.0, 0, 0])
    side = unit(side)
    upv = np.cross(d, side)
    def ring(p):
        return np.array([p + side * w / 2 + upv * h / 2, p - side * w / 2 + upv * h / 2,
                         p - side * w / 2 - upv * h / 2, p + side * w / 2 - upv * h / 2])
    m.add(part, mk.loft([ring(a), ring(b)], tag=tag))


def side_plate(m, part, poly_zy, x0, x1, tag="paint"):
    m.add(part, mk.extrude_zy(poly_zy, min(x0, x1), max(x0, x1), tag=tag))


def plan_plate(m, part, poly_xz, y0, y1, tag="paint"):
    m.add(part, mk.extrude_xz(poly_xz, min(y0, y1), max(y0, y1), tag=tag))


def front_plate(m, part, poly_xy, z0, z1, tag="paint"):
    m.add(part, mk.extrude_xy(poly_xy, min(z0, z1), max(z0, z1), tag=tag))


def ellipse_xz(cx, cz, rx, rz, a0, a1, n):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / (n - 1))),
             cz + rz * math.sin(math.radians(a0 + (a1 - a0) * i / (n - 1)))) for i in range(n)]


def tube(m, part, pts, r, tag="steel", n=6):
    """Hand rails / pipes: a cylinder per segment, slightly overlapping at the corners."""
    for a, b in zip(pts, pts[1:]):
        a = np.asarray(a, float); b = np.asarray(b, float)
        d = unit(b - a) * r * 0.8
        cyl(m, part, a - d, b + d, r, n=n, tag=tag)


def lamp(m, part, x, y, z, r=0.08, facing=(0, 0, 1), tag="lamp"):
    f = np.asarray(facing, float)
    p = np.array([x, y, z], float)
    cyl(m, part, p - f * 0.06, p + f * 0.03, r * 1.15, n=8, tag="black")
    cyl(m, part, p + f * 0.02, p + f * 0.05, r, n=8, tag=tag)


# ------------------------------------------------------------------ cab
# ------------------------------------------------------------------ operator cab and its rider
# The rider is the vanilla player model in its riding pose; the base mod puts its feet origin at the
# seat offset, facing +z. Measured from that origin (unscaled): thighs 0.5 - 0.88 high reaching 0.86
# forward (underside at the hip 0.625), torso 0.75 - 1.5, head 1.5 - 2.0, arms out to x +-0.5.
# The camera defaults to origin + 1.62 (standing eye height) unless camera_positions is given.
# Cabs are sized for real operators, so the rider is shrunk with passenger_display (applied about
# the origin) and the camera moved to the shrunken eye.
RIDER_WIDTH, RIDER_BACK, RIDER_FRONT = 1.0, 0.25, 0.86
RIDER_LEG_LOW, RIDER_SEAT, RIDER_TOP, RIDER_EYE = 0.5, 0.625, 2.0, 1.62


def fit_rider(ix0, ix1, floor, roof, iz0, iz1, pref_x, max_scale=1.0):
    """Largest uniform rider scale (2.5 % steps) inside the interior [ix0, ix1] x [floor, roof] x
    [iz0 (rear), iz1 (front)]: backrest behind the torso, head under the roof, knees clear of the
    front. Returns ((x, y, z) origin, scale)."""
    s = max_scale
    while s > 0.3:
        back = max(0.13 * s + 0.1, RIDER_BACK * s + 0.02)
        if (RIDER_WIDTH * s <= (ix1 - ix0) - 0.04
                and (RIDER_TOP - RIDER_LEG_LOW) * s + 0.15 <= roof - floor
                and back + RIDER_FRONT * s + 0.03 <= iz1 - iz0):
            break
        s = round(s - 0.025, 3)
    slack = (roof - floor) - ((RIDER_TOP - RIDER_LEG_LOW) * s + 0.15)
    oy = roof - 0.05 - min(slack, 0.15) - RIDER_TOP * s
    oz = iz0 + max(0.13 * s + 0.1, RIDER_BACK * s + 0.02)
    half = RIDER_WIDTH * s / 2 + 0.02
    ox = min(max(pref_x, ix0 + half), ix1 - half)
    return (ox, oy, oz), s


def rider_json(name, origin, s):
    """'seats' (with the camera at the rider's eye) and 'passenger_display' for the vehicle JSON."""
    ox, oy, oz = origin
    st = {"name": name, "offset_x": r4(ox), "offset_y": r4(oy), "offset_z": r4(oz), "driver": True,
          "camera_positions": [{"x": r4(ox), "y": r4(oy + RIDER_EYE * s), "z": r4(oz)}]}
    return {"seats": [st], "passenger_display": {"entity_width": s, "entity_height": s}}


def cab_rider(x0, x1, y0, y1, z0, z1, seat, front_slope=0.0, name="operator"):
    """The rider fitted in cab(); call with the cab()'s own arguments. Returns (origin, scale, json)."""
    o, s = fit_rider(x0 + 0.05, x1 - 0.05, y0 + 0.06, y1 - 0.08, z0 + 0.04, z1 - 0.04 - front_slope * 0.5,
                     seat[0])
    return o, s, rider_json(name, o, s)


def cab(m, part, x0, x1, y0, y1, z0, z1, tag="cabin", door_side=1, roof_lip=0.05, seat=None, rear_glass=True,
        front_slope=0.0):
    """Operator cab: pillars, roof, lower panels, glazing on the front and both sides, seat and consoles.
    front_slope leans the windscreen back (metres of setback at the top). seat: preferred seat
    point (only its x is kept - the rider is fitted to the cab, see cab_rider)."""
    belt = y0 + (y1 - y0) * 0.38
    t = 0.05
    # lower tub
    box(m, part, x0, y0, z0, x1, belt, z1, tag)
    # roof (overhangs a little)
    cbox(m, part, x0 - roof_lip, y1 - 0.08, z0 - roof_lip, x1 + roof_lip, y1 + 0.04, z1 + roof_lip, tag, c=0.04)
    # pillars
    zf_top = z1 - front_slope
    for x in (x0 + t / 2, x1 - t / 2):
        bar(m, part, (x, belt - 0.02, z1 - t / 2), (x, y1 - 0.06, zf_top - t / 2), t, t, tag)
        bar(m, part, (x, belt - 0.02, z0 + t / 2), (x, y1 - 0.06, z0 + t / 2), t, t, tag)
        bar(m, part, (x, belt - 0.02, (z0 + z1) / 2 - 0.1), (x, y1 - 0.06, (z0 + zf_top) / 2 - 0.1), 0.04, 0.04, tag)
    # glass (thin closed plates just inside the pillar line)
    gx0, gx1 = x0 + 0.012, x1 - 0.012
    front = [(gx0, belt), (gx1, belt), (gx1, y1 - 0.07), (gx0, y1 - 0.07)]
    m.add(part, mk.loft([np.array([(x, y, z1 - 0.03 - (y - belt) / (y1 - belt) * front_slope) for x, y in front]),
                         np.array([(x, y, z1 - 0.012 - (y - belt) / (y1 - belt) * front_slope) for x, y in front])],
                        tag="glass"))
    for x in (x0 + 0.012, x1 - 0.012):
        s = 1 if x > (x0 + x1) / 2 else -1
        poly = [(z0 + 0.04, belt), (z1 - 0.04, belt), (zf_top - 0.04, y1 - 0.07), (z0 + 0.04, y1 - 0.07)]
        side_plate(m, part, poly, x, x - s * 0.018, "glass")
    if rear_glass:
        box(m, part, x0 + 0.04, belt - 0.01, z0 + 0.012, x1 - 0.04, y1 - 0.07, z0 + 0.03, "glass")
    # door handle and step on the door side
    xd = x1 if door_side > 0 else x0
    box(m, part, xd, belt - 0.12, z0 + 0.25, xd + door_side * 0.03, belt - 0.08, z0 + 0.4, "black")
    # interior: floor, seat (fitted to the rider), consoles
    box(m, part, x0 + 0.03, y0, z0 + 0.03, x1 - 0.03, y0 + 0.06, z1 - 0.03, "black")
    if seat:
        (sx, sy, sz), k, _ = cab_rider(x0, x1, y0, y1, z0, z1, seat, front_slope)
        cushion = sy + RIDER_SEAT * k
        back = sz - 0.13 * k
        cbox(m, part, sx - 0.24 * k, y0 + 0.05, back - 0.02, sx + 0.24 * k, cushion, sz + 0.35 * k, "seat", c=0.03)
        cbox(m, part, sx - 0.24 * k, cushion - 0.02, back - 0.1, sx + 0.24 * k, sy + 1.4 * k, back, "seat", c=0.03)
        for dx in (-1, 1):
            ax = sx + dx * (0.26 * k + 0.07)
            if x0 + 0.05 < ax - 0.06 and ax + 0.06 < x1 - 0.05:
                box(m, part, ax - 0.06, y0 + 0.05, back, ax + 0.06, cushion + 0.12 * k, sz + 0.3 * k, "paint2")
                cyl(m, part, (ax, cushion + 0.12 * k, sz + 0.25 * k), (ax, cushion + 0.32 * k, sz + 0.27 * k), 0.022,
                    n=5, tag="black")
        dash_z = max(sz + RIDER_FRONT * k + 0.02, z1 - 0.3)
        if dash_z < z1 - 0.08:
            box(m, part, max(x0 + 0.05, sx - 0.3), y0 + 0.05, dash_z, min(x1 - 0.05, sx + 0.3), belt + 0.05,
                z1 - 0.05, "paint2")


# ------------------------------------------------------------------ running gear
def crawler_layout(z_half, height, r_end, wheel_r, n_wheels, carrier_r=0.0, n_carrier=0, shoe_t=0.07):
    """Where everything of one crawler goes, in the (y, z) side view (tank practice: the shoes run
    round the idler, the sprocket and under the road wheels; nothing overlaps):
      - idler (front) and sprocket (rear): radius r_end, their tops touching the inside of the top run;
      - road wheels on the bottom run, kept clear of the end wheels and of each other;
      - carrier rollers under the top run;
      - the belt path (centre line of the shoes), convex, in the order the base mod expects
        (front-bottom, up the front, back along the top, down the rear)."""
    t = shoe_t
    R = r_end + t / 2
    zc = z_half - r_end - t
    yc = height - t - r_end
    gap = 0.04
    # road wheels: first / last position clear of the end wheels
    dy = yc - (t + wheel_r)
    need = r_end + wheel_r + gap
    dz = math.sqrt(max(need * need - dy * dy, 0.0)) if need > abs(dy) else 0.0
    z_lo, z_hi = -zc + dz, zc - dz
    n = max(2, n_wheels)
    while n > 2 and (z_hi - z_lo) / (n - 1) < 2 * wheel_r + 0.03:
        n -= 1
    wheels = [(t + wheel_r, z) for z in np.linspace(z_lo, z_hi, n)]
    carriers = []
    if n_carrier:
        cz_lim = zc - (r_end + carrier_r + 0.08)
        zs = [0.0] if n_carrier == 1 else list(np.linspace(-cz_lim * 0.55, cz_lim * 0.55, n_carrier))
        carriers = [(height - t - carrier_r, z) for z in zs]
    # path
    touching = (yc - r_end) - t < 0.03
    a0 = -90.0 if touching else -45.0
    front = [(yc + R * math.sin(math.radians(a)), zc + R * math.cos(math.radians(a)))
             for a in np.linspace(a0, 90.0, 7)]
    rear = [(yc + R * math.sin(math.radians(a)), -zc + R * math.cos(math.radians(a)))
            for a in np.linspace(90.0, 180.0 - a0, 7)]
    path = []
    if not touching:
        path.append((t / 2, z_hi + wheel_r * 0.5))
    path += front + rear
    if not touching:
        path.append((t / 2, z_lo - wheel_r * 0.5))
    return {"t": t, "zc": zc, "yc": yc, "r_end": r_end, "wheels": wheels, "wheel_r": wheel_r,
            "carriers": carriers, "carrier_r": carrier_r, "path": path}


def path_placements(path, spacing, count):
    """Same placement rule as the base mod's crawler tracks: (y, z, angle degrees) of link i at
    belt travel 0."""
    n = len(path)
    cum = [0.0]
    for i in range(n):
        a, b = path[i], path[(i + 1) % n]
        cum.append(cum[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = cum[-1]
    out = []
    for i in range(count):
        d = (i * spacing) % total
        k = 0
        while k < n - 1 and cum[k + 1] < d:
            k += 1
        a, b = path[k], path[(k + 1) % n]
        f = (d - cum[k]) / max(cum[k + 1] - cum[k], 1e-9)
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f,
                    math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))))
    return out


def track_link(m, part, x_in, x_out, length, t, place=None, tag="track"):
    """One shoe in the base mod's link frame (local +Y = outward, local Z along the belt), centred on
    y = z = 0, or already moved to a placement (y, z, angle) for links drawn by this addon."""
    x0, x1 = sorted((x_in, x_out))
    shells = [mk.box((x0 + x1) / 2, 0, 0, x1 - x0, t, length * 0.9, tag=tag),
              mk.box((x0 + x1) / 2, t / 2 + 0.015, 0, (x1 - x0) * 0.96, 0.03, 0.05, tag=tag)]
    for sh in shells:
        if place is not None:
            y, z, ang = place
            sh.rotate(mk.rot_axis((1, 0, 0), ang + 90.0), pivot=(0, 0, 0))
            sh.translate(0, y, z)
        m.add(part, sh)


def crawler(m, side, x_in, x_out, z_half, height, r_end, wheel_r, n_wheels, carrier_r=0.0, n_carrier=0,
            frame_part="body", frame_tag="paint2", link_len=0.19, sprocket_rear=True, addon_parent=None,
            shoe_t=0.07):
    """A complete crawler for one side ("l" / "r"): frame, idler, sprocket, road wheels, carrier rollers
    and shoes. Returns the JSON for it:
      - normally the base mod draws and drives it: {"crawler_tracks": [...], "track_roller_parts": [...]};
      - with addon_parent (a crawler that moves, e.g. widening) everything is drawn by this addon, riding
        on that part: {"crawler_tracks": [belt only], "joints": [...], "crawlers": [...]}."""
    L = crawler_layout(z_half, height, r_end, wheel_r, n_wheels, carrier_r, n_carrier, shoe_t)
    s = 1 if x_in > 0 else -1
    xc = (x_in + x_out) / 2
    t, zc, yc = L["t"], L["zc"], L["yc"]
    path = L["path"]
    spacing = armor.link_spacing([(y, z) for y, z in path], link_len)
    total = armor.path_length(path)
    count = int(round(total / spacing))
    roller_part = lambda name: "$%s_%s" % (name, side)
    rollers = []                                   # (part, y, z, radius)
    fpart = addon_parent or frame_part
    # frame: between the road wheels and the carrier rollers, clear of the end wheels
    fy0 = t + 2 * wheel_r + 0.02
    fy1 = (height - t - 2 * carrier_r - 0.02) if n_carrier else (yc + L["r_end"] * 0.35)
    fz = zc - L["r_end"] - 0.03
    fi, fo = x_in + s * 0.06, x_out - s * 0.06
    side_plate(m, fpart, [(-fz, fy0), (fz, fy0), (fz + 0.12, (fy0 + fy1) / 2), (fz, fy1), (-fz, fy1),
                          (-fz - 0.12, (fy0 + fy1) / 2)], fi, fo, frame_tag)
    for zz in (zc, -zc):                            # yokes carrying the end wheels
        bar(m, fpart, (x_in + s * 0.02, yc, zz), (x_in + s * 0.02, (fy0 + fy1) / 2,
            zz - np.sign(zz) * (L["r_end"] + 0.1)), 0.06, 0.12, frame_tag)
    # end wheels
    spr_z, idl_z = (-zc, zc) if sprocket_rear else (zc, -zc)
    p = roller_part("sprocket")
    cyl(m, p, (x_in + s * 0.03, yc, spr_z), (x_out - s * 0.03, yc, spr_z), L["r_end"] * 0.97, n=12, tag="steel")
    for k in range(10):                             # teeth
        a = 2 * math.pi * k / 10
        tz, ty = math.cos(a) * L["r_end"], math.sin(a) * L["r_end"]
        box(m, p, xc - s * 0.08 - 0.03, yc + ty - 0.035, spr_z + tz - 0.035, xc - s * 0.08 + 0.03, yc + ty + 0.035,
            spr_z + tz + 0.035, "steel")
    cyl(m, p, (x_out - s * 0.03, yc, spr_z), (x_out + s * 0.01, yc, spr_z), L["r_end"] * 0.45, n=8, tag="paint2")
    rollers.append((p, yc, spr_z, L["r_end"]))
    p = roller_part("idler")
    cyl(m, p, (x_in + s * 0.03, yc, idl_z), (x_out - s * 0.03, yc, idl_z), L["r_end"] * 0.97, n=14, tag="steel")
    for k in range(5):
        a = 2 * math.pi * k / 5
        box(m, p, x_out - s * 0.02, yc + math.sin(a) * L["r_end"] * 0.6 - 0.04, idl_z + math.cos(a) * L["r_end"] * 0.6 - 0.04,
            x_out + s * 0.01, yc + math.sin(a) * L["r_end"] * 0.6 + 0.04, idl_z + math.cos(a) * L["r_end"] * 0.6 + 0.04,
            "paint2")
    rollers.append((p, yc, idl_z, L["r_end"]))
    # road wheels and carrier rollers (each with a mark on its face, so the turning shows)
    for i, (y, z) in enumerate(L["wheels"]):
        p = roller_part("wheel%d" % i)
        cyl(m, p, (x_in + s * 0.07, y, z), (x_out - s * 0.07, y, z), wheel_r, n=10, tag="steel")
        box(m, p, x_out - s * 0.075, y + wheel_r * 0.3, z - 0.02, x_out - s * 0.05, y + wheel_r * 0.85, z + 0.02,
            "paint2")
        rollers.append((p, y, z, wheel_r))
    for i, (y, z) in enumerate(L["carriers"]):
        p = roller_part("carrier%d" % i)
        cyl(m, p, (xc - s * 0.12, y, z), (xc + s * 0.12, y, z), carrier_r, n=8, tag="steel")
        cyl(m, fpart, (x_in + s * 0.04, y, z), (xc - s * 0.1, y, z), carrier_r * 0.4, n=6, tag=frame_tag)
        rollers.append((p, y, z, carrier_r))
    belt = {"part": "$crawler_%s" % side, "x": round(xc, 4), "link_spacing": round(spacing, 5),
            "path": [{"y": round(y, 4), "z": round(z, 4)} for y, z in path]}
    if addon_parent is None:
        track_link(m, belt["part"], x_in, x_out, spacing, t)
        return {"crawler_tracks": [belt],
                "track_roller_parts": [{"part": p, "pivot_x": round(xc, 4), "pivot_y": round(y, 4),
                                        "pivot_z": round(z, 4), "rotations_per_block": round(1 / (2 * math.pi * r), 4)}
                                       for p, y, z, r in rollers]}
    # drawn by this addon: one group per link at its travel-0 place, rollers turning with the belt
    belt["part"] = "$belt_%s" % side
    prefix = "$link_%s_" % side
    for i, place in enumerate(path_placements(path, spacing, count)):
        track_link(m, prefix + str(i), x_in, x_out, spacing, t, place=place)
    joints = [{"part": p, "parent": addon_parent, "pivot_x": 0, "pivot_y": round(y, 4), "pivot_z": round(z, 4),
               "axis_x": 1, "axis_y": 0, "axis_z": 0, "mode": "belt", "belt": belt["part"],
               "factor": round(-360.0 / (2 * math.pi * r), 3)} for p, y, z, r in rollers]
    crawlers = [{"belt": belt["part"], "parent": addon_parent, "link_prefix": prefix,
                 "link_spacing": belt["link_spacing"], "path": belt["path"]}]
    return {"crawler_tracks": [belt], "joints": joints, "crawlers": crawlers}


def merge_crawlers(spec, results):
    """Adds crawler() results to a builder's spec."""
    top = spec.setdefault("top", {})
    con = spec.setdefault("construction", {})
    for r in results:
        top.setdefault("crawler_tracks", []).extend(r.get("crawler_tracks", []))
        if r.get("track_roller_parts"):
            top.setdefault("track_roller_parts", []).extend(r["track_roller_parts"])
        if r.get("joints"):
            con.setdefault("joints", []).extend(r["joints"])
        if r.get("crawlers"):
            con.setdefault("crawlers", []).extend(r["crawlers"])
    return spec


def wheel(m, part, cx, cy, cz, r, w, dual=False):
    """Tyre with tread blocks, rim and hub. Outer face is on the side away from the centre line.
    dual=True: two tyres side by side (w = overall width), as on truck rear axles."""
    if dual:
        s = 1 if cx > 0 else -1
        tw = w * 0.47
        wheel(m, part, cx + s * (w - tw) / 2, cy, cz, r, tw)
        wheel(m, part, cx - s * (w - tw) / 2, cy, cz, r, tw)
        cyl(m, part, (cx - s * w / 2 + s * 0.05, cy, cz), (cx + s * w / 2 - s * 0.05, cy, cz), r * 0.45, n=10, tag="rim")
        return
    s = 1 if cx > 0 else -1
    xo, xi = cx + s * w / 2, cx - s * w / 2
    cyl(m, part, (xi, cy, cz), (xo, cy, cz), r * 0.93, n=16, tag="tyre")
    # tread: blocks around the circumference (they show the wheel turning)
    n = 14
    for i in range(n):
        a = 2 * math.pi * i / n
        dy, dz = math.sin(a), math.cos(a)
        p0 = (xi + s * 0.04, cy + dy * (r * 0.9), cz + dz * (r * 0.9))
        p1 = (xo - s * 0.04, cy + dy * (r * 0.9), cz + dz * (r * 0.9))
        bar(m, part, p0, p1, 0.09 * r / 0.55, 0.1 * r, "tyre", up=(0, dy, dz))
    # rim + hub on the outer face (dished)
    cyl(m, part, (xo - s * 0.06, cy, cz), (xo + s * 0.005, cy, cz), r * 0.6, n=12, tag="rim")
    cyl(m, part, (xo - s * 0.02, cy, cz), (xo + s * 0.05, cy, cz), r * 0.22, n=8, tag="steel")
    for i in range(6):
        a = 2 * math.pi * i / 6
        cyl(m, part, (xo, cy + math.sin(a) * r * 0.36, cz + math.cos(a) * r * 0.36),
            (xo + s * 0.03, cy + math.sin(a) * r * 0.36, cz + math.cos(a) * r * 0.36), 0.025, n=5, tag="steel")
    # inner face rim (so the tyre is not hollow-looking from the inside)
    cyl(m, part, (xi + s * 0.06, cy, cz), (xi - s * 0.005, cy, cz), r * 0.5, n=10, tag="rim")


# ------------------------------------------------------------------ hydraulic cylinders that follow joints
def rot(v, axis, deg):
    """Right-handed rotation of v about unit axis (Rodrigues)."""
    v = np.asarray(v, float); k = unit(axis); a = math.radians(deg)
    return v * math.cos(a) + np.cross(k, v) * math.sin(a) + k * np.dot(k, v) * (1 - math.cos(a))


def ram_lengths(base, attach, pivot, axis, lo, hi, factor=1.0, offset=0.0, steps=24):
    """Shortest and longest base -> attach distance while the child turns through its range
    (channel lo..hi, angle = channel * factor + offset; the model is the pose at angle 0)."""
    base = np.asarray(base, float); attach = np.asarray(attach, float); pivot = np.asarray(pivot, float)
    lens = [np.linalg.norm(pivot + rot(attach - pivot, axis, c * factor + offset) - base)
            for c in np.linspace(lo, hi, steps)]
    lens.append(np.linalg.norm(attach - base))
    return min(lens), max(lens)


def r4(v):
    return round(float(v), 4)


def ram_joints(parts, parent, base, attach, child, axis, u):
    """Exact joints for a ram: the barrel aims at the attachment point on `child`, every further stage
    takes an equal share of the growth in length (addon joint modes "aim" / "reach")."""
    tgt = {"part": child, "x": r4(attach[0]), "y": r4(attach[1]), "z": r4(attach[2])}
    anc = {"part": parts[0], "x": r4(base[0]), "y": r4(base[1]), "z": r4(base[2])}
    ax = [r4(a) for a in unit(axis)]
    joints = [{"part": parts[0], "parent": parent, "pivot_x": r4(base[0]), "pivot_y": r4(base[1]),
               "pivot_z": r4(base[2]), "axis_x": ax[0], "axis_y": ax[1], "axis_z": ax[2], "mode": "aim",
               "target": tgt}]
    if not parent:
        joints[0].pop("parent")
    for i in range(1, len(parts)):
        joints.append({"part": parts[i], "parent": parts[i - 1], "pivot_x": 0, "pivot_y": 0, "pivot_z": 0,
                       "axis_x": r4(u[0]), "axis_y": r4(u[1]), "axis_z": r4(u[2]), "mode": "reach",
                       "target": tgt, "anchor": anc, "factor": r4(1.0 / (len(parts) - 1))})
    return joints


def actuator(m, name, parent, base, attach, child, pivot, axis, lo, hi, r, factor=1.0, offset=0.0, tag="paint",
             barrel_frac=0.62, stages=None):
    """A hydraulic ram from `base` (on `parent`) to `attach` (a point on `child`, which turns about
    pivot/axis through channel lo..hi): barrel $<name>, and rod $<name>_s1 (or more stages when the
    length changes too much for one). Modelled in the rest pose; the joints follow the child exactly.
    Returns (joints, stage count)."""
    base = np.asarray(base, float); attach = np.asarray(attach, float)
    d = attach - base; L0 = np.linalg.norm(d); u = d / L0
    lmin, lmax = ram_lengths(base, attach, pivot, axis, lo, hi, factor, offset)
    # one rod while it can stay half inside the barrel, otherwise as many nested stages as needed
    S = lmin * 0.92
    n = stages or max(2, int(np.ceil((lmax - S) / (S * 0.82))) + 1)
    parts = ["$" + name] + ["$%s_s%d" % (name, i) for i in range(1, n)]
    if n == 2 and lmax <= 1.75 * lmin:
        # classic two-part ram: barrel ~ the shortest length, rod sliding out of it
        # long enough that the rod is still inside it at full stroke (rod starts lmax - 0.95 lmin out)
        barrel = min(max(L0 * barrel_frac, lmax - 0.85 * lmin), lmin * 0.9)
        cyl(m, parts[0], base - u * r * 0.6, base + u * barrel, r, n=8, tag=tag)
        cyl(m, parts[0], base + u * (barrel - 0.04), base + u * (barrel + 0.02), r * 1.12, n=8, tag=tag)
        cyl(m, parts[1], base + u * max(L0 - lmin * 0.95, r), attach, r * 0.55, n=6, tag="chrome")
        cyl(m, parts[1], attach - u * 0.06, attach + u * 0.06, r * 0.9, n=6, tag="steel")
    else:
        step = (L0 - S) / (n - 1)
        for i, part in enumerate(parts):
            rr = r * (1.0 - 0.17 * i)
            a0 = base + u * (step * i)
            cyl(m, part, a0 - (u * r * 0.6 if i == 0 else 0), a0 + u * S, rr, n=8, tag=tag if i == 0 else "chrome")
            if i < n - 1:
                cyl(m, part, a0 + u * (S - 0.08), a0 + u * S, rr * 1.12, n=8, tag=tag)
        cyl(m, parts[-1], attach - u * 0.08, attach + u * 0.08, r * 0.9, n=6, tag="steel")
    return ram_joints(parts, parent, base, attach, child, axis, u), n


# ------------------------------------------------------------------ texture
def finish(m, size=1024, colors=None, stripes=None, grime_top=1.0, extra_paint=None):
    from meshkit.texture import texture_model, value_noise
    from meshkit import detail
    cols = dict(PROJECTED); cols.update(colors or {})
    flat = {k: v for k, v in FLAT.items()}

    def paint(atlas, model):
        detail.tone(atlas, ["paint", "paint2", "cabin", "white", "red", "orange", "stripe"], amount=0.04)
        if stripes is not None:
            def f(X, Y, Z, cur):
                band = np.floor((X * stripes[0] + Y + Z * stripes[1]) / 0.18).astype(int) % 2 == 0
                return np.where(band[:, None], np.array([30, 30, 30], np.float32), cur), None
            atlas.paint3d(f, tags=["stripe"])
        def earth(X, Y, Z, cur):
            n = value_noise(X * 3 + Z, Y * 3 + Z * 2, 0.4, seed=7)
            return cur * (0.8 + 0.35 * n[:, None]), None
        atlas.paint3d(earth, tags=["earth"])
        if extra_paint:
            extra_paint(atlas, model)
        detail.ink(atlas, ["paint", "paint2", "cabin", "white", "red", "orange", "stripe"], strength=0.38)
        detail.grime(atlas, ["paint", "paint2", "cabin", "white", "red", "orange", "stripe"], y_top=grime_top,
                     amount=0.5)
        atlas.paint_alpha(150, tags=["glass"])

    texture_model(m, (size, size), colors=cols, flat=flat, paint=paint, glass_tags=("glass",))


def report(m, name):
    from meshkit import check
    rep = check.report(m, contacts=False)
    s = rep["summary"]
    return "%s: tris=%s size=%s issues=%d %s" % (name, s.get("triangles", s.get("tris")), s.get("size"),
                                                  rep["issue_count"], rep["issue_kinds"])


# ------------------------------------------------------------------ JSON helpers
def J(part, pivot, axis, mode="rotate", channel=None, parent=None, **extra):
    j = {"part": part}
    if parent:
        j["parent"] = parent
    j.update({"pivot_x": r4(pivot[0]), "pivot_y": r4(pivot[1]), "pivot_z": r4(pivot[2]),
              "axis_x": r4(axis[0]), "axis_y": r4(axis[1]), "axis_z": r4(axis[2]), "mode": mode})
    if channel:
        j["channel"] = channel
    j.update(extra)
    return j


def pt(part, p):
    d = {"x": r4(p[0]), "y": r4(p[1]), "z": r4(p[2])}
    return {"part": part, **d} if part else d


def seat(name, p, driver=True):
    return {"name": name, "offset_x": r4(p[0]), "offset_y": r4(p[1]), "offset_z": r4(p[2]), "driver": driver}


def wheel_parts(entries):
    out = []
    for part, (x, y, z), steer in entries:
        w = {"part": part, "pivot_x": r4(x), "pivot_y": r4(y), "pivot_z": r4(z)}
        if steer:
            w["steer_angle"] = steer
        out.append(w)
    return out
