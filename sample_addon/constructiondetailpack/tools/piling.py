"""Pile drivers (original designs).

Class reference (general published ranges, compared for structure and size only):
  Compact leader rigs: crawler base ~3.2 m wide, ~12 m box-section leader pinned at its foot and held
    by backstays at about half its height, laid back over the machine for travel; drop hammer, or a
    rotary head pressing in a steel pipe pile.
  Large three-point rigs: round-pipe leader ~20-30 m pinned at its foot and held by two backstays at
    about half its height (three-point support), crawlers that widen from ~3.5 m to ~4.9 m, a rotary
    head high on the leader turning and pressing in a steel pipe pile (~0.8 m) through a guide at the foot.
"""
import math

import numpy as np

from common import (cab_rider, box, cbox, cyl, bar, side_plate, plan_plate, ellipse_xz, tube, lamp, cab, crawler, merge_crawlers, actuator, model, mech,
                    J, pt, seat)

SWING_ALARM = {"sound": "minecraft:block.note_block.bit", "volume": 0.6, "pitch": 1.8, "interval": 8}


def outriggers(m, mounts, y_beam, beam_h, stroke, pad_y, pad_r, rod_top, housing_top, parent="$upper"):
    """Four outriggers under the corners of the upper structure. Each box beam slides out diagonally
    (first half of "outrigger"), then its jack cylinder pushes the rod and pad down to the ground
    (second half). They are children of the upper structure, so they swing with it.
    mounts = [(name, (x, z) of the jack cylinder when stowed, (sx, sz) outward diagonal)]."""
    joints = []
    w = beam_h * 1.25
    hr = w * 0.62
    for name, (x, z), (sx, sz) in mounts:
        d = np.array([sx, 0.0, sz]) / math.hypot(sx, sz)
        outer = np.array([x, y_beam, z])
        inner = outer - d * (stroke + 0.5)
        g, leg = "$" + name, "$%s_leg" % name
        bar(m, g, inner, outer + d * hr, w, beam_h, "paint2")
        cyl(m, g, (x, y_beam - beam_h / 2 - 0.03, z), (x, housing_top, z), hr, n=10, tag="paint")
        cyl(m, g, (x, housing_top - 0.08, z), (x, housing_top, z), hr * 1.12, n=10, tag="paint2")
        cyl(m, leg, (x, pad_y + 0.07, z), (x, rod_top, z), hr * 0.55, n=8, tag="chrome")
        cyl(m, leg, (x, pad_y, z), (x, pad_y + 0.07, z), pad_r, n=14, tag="paint2")
        joints.append(J(g, (0, 0, 0), d, mode="slide", channel="outrigger", parent=parent,
                        factor=round(2 * stroke, 4), min=0.0, max=round(stroke, 4)))
        joints.append(J(leg, (0, 0, 0), (0, -1, 0), mode="slide", channel="outrigger", parent=g,
                        factor=round(2 * pad_y, 4), offset=round(-pad_y, 4), min=0.0, max=round(pad_y, 4)))
    return joints


def corner_mounts(front, rear_pts, x, offset):
    """Front corners at (+-x, front) and rear points rear_pts = (x, z) on the tail, each pushed out by
    `offset` along its diagonal."""
    k = offset / math.sqrt(2)
    out = []
    for side, s in (("l", 1), ("r", -1)):
        out.append(("outrigger_f" + side, (s * (x + k), front + k), (s, 1)))
        out.append(("outrigger_r" + side, (s * (rear_pts[0] + k), rear_pts[1] - k), (s, -1)))
    return out


def tail_point(hw, tail, angle_deg, cw=True):
    """A point on the counterweight's outline (see upper_structure) at this angle."""
    zc = -tail * 0.65
    a = math.radians(angle_deg)
    return abs(hw * math.cos(a)), zc + (tail - abs(zc)) * math.sin(a)


def box_leader(m, part, hw, z0, z1, y0, y1, seg=3.0, tag="orange"):
    """Box-section leader (welded steel box) with bolted segment flanges, two front guide rails and a
    ladder on the back face."""
    cbox(m, part, -hw, y0, z0, hw, y1, z1, tag, c=0.04)
    n = max(1, int(round((y1 - y0) / seg)))
    for i in range(1, n):
        y = y0 + i * (y1 - y0) / n
        box(m, part, -hw - 0.04, y - 0.05, z0 - 0.04, hw + 0.04, y + 0.05, z1 + 0.04, tag)
    for x in (-hw * 0.72, hw * 0.72):
        box(m, part, x - 0.045, y0 + 0.1, z1 - 0.01, x + 0.045, y1 - 0.05, z1 + 0.15, "steel")
    for x in (-hw * 0.55, hw * 0.55):
        box(m, part, x - 0.02, y0 + 0.4, z0 - 0.2, x + 0.02, y1 - 0.4, z0 - 0.16, "steel")
    k = int((y1 - y0 - 0.8) / 0.3)
    for i in range(k):
        y = y0 + 0.5 + i * 0.3
        box(m, part, -hw * 0.55, y, z0 - 0.19, hw * 0.55, y + 0.03, z0, "steel")


def pipe_leader(m, part, r, cz, y0, y1, seg=4.5, guide_x=0.3, tag="orange"):
    """Round steel-pipe leader in bolted sections, with two guide pipes on the front."""
    cyl(m, part, (0, y0, cz), (0, y1, cz), r, n=14, tag=tag)
    n = max(1, int(round((y1 - y0) / seg)))
    for i in range(1, n):
        y = y0 + i * (y1 - y0) / n
        cyl(m, part, (0, y - 0.06, cz), (0, y + 0.06, cz), r + 0.07, n=14, tag=tag)
    for x in (-guide_x, guide_x):
        cyl(m, part, (x, y0 + 0.1, cz + r + 0.02), (x, y1 - 0.1, cz + r + 0.02), 0.075, n=6, tag="steel")
        for i in range(int((y1 - y0) / 1.5)):
            y = y0 + 0.5 + i * 1.5
            box(m, part, x - 0.04, y, cz + r * 0.6, x + 0.04, y + 0.08, cz + r + 0.02, "steel")


def pile(m, part, cx, cz, y0, y1, r, wing=True):
    """Steel pipe pile for rotary press-in: a plain tube with a tip wing (about one turn of helical
    blade) and a pointed shoe. Driven by the rotary head, which grips its top. wing=False: a plain
    driven pipe pile with a closed shoe (drop hammer)."""
    cyl(m, part, (cx, y0 + 0.25, cz), (cx, y1, cz), r, n=14, tag="steel")
    cyl(m, part, (cx, y0, cz), (cx, y0 + 0.25, cz), r * 0.35, n=10, tag="steel", r1=r)    # shoe
    n = 14
    prev = None
    for i in range(n + 1 if wing else 0):
        a = 2 * math.pi * 1.15 * i / n
        y = y0 + 0.25 + 0.55 * i / n
        p = np.array([cx + math.cos(a) * r * 1.45, y, cz + math.sin(a) * r * 1.45])
        if prev is not None:
            radial = np.array([math.cos(a), 0, math.sin(a)])
            bar(m, part, prev, p, r * 0.95, 0.04, "steel", up=np.cross(p - prev, radial))
        prev = p
    for y in np.arange(y0 + 1.0, y1 - 0.3, 1.0):                                         # depth marks
        cyl(m, part, (cx, y - 0.03, cz), (cx, y + 0.03, cz), r * 1.01, n=14, tag="red")


def rotary_rig(m, parent, cz, grip, pile_r, cap, tool=None, tool_len=0.0, head="$rotary_head", wing=True, hide_in=None):
    """Joints (and pile / tool geometry) of a rotary rig. The head ($rotary_head, drawn by the caller
    with its grip at y = grip) slides on the leader by "head" (grip height); the steel pipe pile hangs
    from the cap, `cap` below the grip, at "pile_top" and is "pile_length" long: its shaft stretches
    and its tip (shoe and wing) moves down, while $pile_spin turns both with "rpm". Tools:
      - "drive_rod": always fitted; normally gripped low (its foot is the cap); "tool" 1 slides it
        tool_len down, gripped at its top, so the cap reaches below the head;
      - "follower" (ヤットコ): set under the head like a pile - it appears (joint mode "show") when
        "tool" reaches 1, tool_len long, and disappears when it goes back."""
    top = grip - cap
    tip_h = 0.85
    shaft = top - tip_h
    cyl(m, "$pile_shaft", (0, tip_h - 0.02, cz), (0, top, cz), pile_r, n=14, tag="steel")
    cyl(m, "$pile_shaft", (0, top - 0.12, cz), (0, top, cz), pile_r * 1.06, n=14, tag="steel")
    pile(m, "$pile_tip", 0.0, cz, 0.0, tip_h, pile_r, wing=wing)
    joints = [
        J(head, (0, 0, 0), (0, 1, 0), mode="slide", channel="head", offset=round(-grip, 4), parent=parent),
        J("$pile", (0, 0, 0), (0, 1, 0), mode="slide", channel="pile_top", offset=round(-top, 4), parent=parent),
        J("$pile_spin", (0, 0, cz), (0, 1, 0), mode="spin", channel="rpm", factor=0.3, parent="$pile"),
        J("$pile_shaft", (0, top, cz), (0, 1, 0), mode="scale", channel="pile_length", factor=round(1 / shaft, 5),
          offset=round(-tip_h / shaft, 5), min=0.02, parent="$pile_spin"),
        J("$pile_tip", (0, 0, 0), (0, 1, 0), mode="slide", channel="pile_length", factor=-1.0, offset=round(top, 4),
          parent="$pile_spin"),
    ]
    if tool == "drive_rod":
        D = "$drive_rod"
        cyl(m, D, (0, top, cz), (0, top + 0.18, cz), pile_r * 1.2, n=12, tag="paint2")          # cap
        cyl(m, D, (0, top + 0.18, cz), (0, top + 0.3, cz), pile_r * 0.8, n=10, tag="paint2")
        cyl(m, D, (0, top + 0.3, cz), (0, top + tool_len + 0.7, cz), pile_r * 0.55, n=10, tag="chrome")
        for y in (top + 0.55, top + tool_len + 0.55):                                          # grip collars
            cyl(m, D, (0, y, cz), (0, y + 0.14, cz), pile_r * 0.75, n=10, tag="steel")
        joints.append(J(D, (0, 0, 0), (0, 1, 0), mode="slide", channel="tool", factor=round(-tool_len, 4),
                        parent=head))
    elif tool == "follower":
        F = "$follower"
        # shown only once it is set (tool 1); hidden (shrunk into this point inside the head) otherwise
        hide = grip + 0.3 if hide_in is None else hide_in
        cyl(m, F, (0, top - tool_len, cz), (0, top - tool_len + 0.2, cz), pile_r * 1.15, n=14, tag="paint2")
        cyl(m, F, (0, top - tool_len + 0.2, cz), (0, hide, cz), pile_r * 1.04, n=14, tag="orange")
        for y in np.arange(top - tool_len + 1.0, hide - 0.3, 1.0):
            cyl(m, F, (0, y - 0.03, cz), (0, y + 0.03, cz), pile_r * 1.06, n=14, tag="paint2")
        joints.append(J(F, (0, hide, cz), (0, 1, 0), mode="show", channel="tool", parent=head))
    return joints


def steady(m, parent, cz, r_mid, band, y0, y1, lug_to, open_deg=100.0):
    """Pile steady (振れ止め) at the leader foot: two half rings, hollow in the middle where the pile
    passes, that close round the pile from left and right. Each swings outward about a vertical hinge
    pin behind its rear corner ("steady" 0 = closed, 1 = open). Returns the joints."""
    joints = []
    for s, side in ((1, "l"), (-1, "r")):
        g = "$steady_" + side
        n = 8
        pts = [(s * r_mid * math.cos(math.radians(a)), cz + r_mid * math.sin(math.radians(a)))
               for a in np.linspace(-90.0, 90.0, n + 1)]
        for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
            bar(m, g, (x0, (y0 + y1) / 2, z0), (x1, (y0 + y1) / 2, z1), band, y1 - y0, "orange")
        hinge = np.array([s * r_mid, (y0 + y1) / 2, cz - r_mid])
        a45 = np.array([s * r_mid * 0.7071, (y0 + y1) / 2, cz - r_mid * 0.7071])
        bar(m, g, hinge, a45, band * 0.8, (y1 - y0) * 0.8, "orange")
        cyl(m, g, hinge + [0, -(y1 - y0) / 2 - 0.04, 0], hinge + [0, (y1 - y0) / 2 + 0.04, 0], band * 0.45, n=8,
            tag="steel")
        # hinge lugs reaching back into the leader (lug_to = (x half, z) inside the leader's foot)
        for yy in (y1 + 0.08, y0 - 0.08):
            bar(m, parent, (hinge[0], yy, hinge[2] + 0.08), (s * lug_to[0], yy, lug_to[1]), 0.16, 0.08, "paint2")
        joints.append(J(g, tuple(float(v) for v in hinge), (0, s, 0), channel="steady", factor=open_deg, parent=parent))
    return joints


def upper_structure(m, hw, front, tail, deck, cw_top, hood_top, cab_box, seat_p):
    U = "$upper"
    y0, y1 = deck
    tl = ellipse_xz(0, 0, hw, tail, 200, 340, 9)
    plan_plate(m, U, [(hw, front), (-hw, front), (-hw, tl[0][1])] + tl + [(hw, tl[-1][1])], y0, y1, "paint2")
    zc = -tail * 0.65
    cw = ellipse_xz(0, zc, hw, tail - abs(zc), 180, 360, 13)
    plan_plate(m, U, cw, y1 - 0.05, cw_top, "paint")
    plan_plate(m, U, [(x * 1.004, z * 1.002) for x, z in cw], y1 + 0.05, y1 + 0.25, "stripe")
    cx0, cx1, cy0, cy1, cz0, cz1 = cab_box
    side_plate(m, U, [(zc, y1), (cz1 - 0.3, y1), (cz1 - 0.3, hood_top - 0.35), (cz1 - 0.7, hood_top),
                      (zc, hood_top)], -hw, -0.62, "paint")
    for i in range(int((cz1 - 0.6 - zc) / 0.22)):
        z = zc + 0.2 + i * 0.22
        box(m, U, -hw - 0.02, y1 + 0.2, z, -hw + 0.01, hood_top - 0.2, z + 0.11, "grille")
    cbox(m, U, cx0, y1, zc, hw, hood_top - 0.2, cz0 - 0.05, "paint", c=0.05)
    cyl(m, U, (-hw + 0.4, hood_top, zc + 0.5), (-hw + 0.4, hood_top + 0.55, zc + 0.5), 0.07, n=8, tag="black")
    tube(m, U, [(-hw + 0.08, hood_top, zc + 0.2), (-hw + 0.08, hood_top + 0.4, zc + 0.3),
                (-hw + 0.08, hood_top + 0.4, cz1 - 0.8), (-hw + 0.08, hood_top - 0.1, cz1 - 0.5)], 0.03)
    cab(m, U, cx0, cx1, cy0, cy1, cz0, cz1, door_side=1, seat=seat_p, front_slope=0.12)
    lamp(m, U, (cx0 + cx1) / 2, cy1 + 0.08, cz1 - 0.05, 0.07)
    return zc


# ====================================================================== compact rigs
S_SWING = np.array([0.0, 1.05, 0.0])
S_MAST = np.array([0.0, 1.5, 2.2])
S_PILE = np.array([0.0, 1.5, 3.35])
S_SEAT = (1.0, 1.4, 0.95)
S_CAB = (0.55, 1.45, 1.35, 3.0, 0.2, 1.75)
S_TOP = 13.5
S_STAY_Y = 7.0          # backstay attachment height on the leader


def compact_base(m):
    """Returns (crawler JSON (see common.crawler), outrigger joints)."""
    tracks = []
    for s in (1, -1):
        tracks.append(crawler(m, "l" if s > 0 else "r", s * 1.0, s * 1.6, 2.4, 0.95, r_end=0.4, wheel_r=0.14,
                              n_wheels=7, carrier_r=0.1, n_carrier=2, link_len=0.2))
    cbox(m, "body", -1.02, 0.35, -1.6, 1.02, 1.0, 1.6, "paint2", c=0.08)
    cyl(m, "body", (0, 0.92, 0), (0, 1.07, 0), 1.0, n=22, tag="paint2")
    for z in (1.85, -1.85):
        box(m, "body", -0.98, 0.4, z - 0.2, 0.98, 0.75, z + 0.2, "paint2")
    upper_structure(m, 1.5, 1.8, 2.8, (1.05, 1.35), 2.4, 2.45, S_CAB, S_SEAT)
    U = "$upper"
    # outriggers under the corners of the upper structure, out diagonally past the crawlers
    outs = outriggers(m, corner_mounts(1.8, tail_point(1.5, 2.8, 225), 1.5, 0.2), 1.16, 0.14, 1.1, 1.0, 0.28,
                      2.35, 2.45)
    # winch drums ahead of the engine, mast foot bracket
    for i, z in enumerate((1.05, 1.55)):
        cyl(m, U, (-1.1, 1.62, z), (0.2, 1.62, z), 0.2, n=12, tag="rope")
    for x in (-1.15, 0.25):
        side_plate(m, U, [(0.8, 1.33), (1.8, 1.33), (1.8, 1.9), (1.1, 1.95)], x - 0.04, x + 0.04, "paint2")
    for x in (-0.48, 0.38):
        side_plate(m, U, [(1.5, 1.33), (2.45, 1.33), (2.45, 1.75), (2.0, 1.95), (1.6, 1.75)], x, x + 0.1, "paint")
    # rest for the folded leader
    for x in (-0.4, 0.4):
        bar(m, U, (x, 1.35, -2.4), (x * 0.7, 2.05, -2.5), 0.12, 0.12, "orange", up=(0, 0, 1))
    box(m, U, -0.45, 1.98, -2.65, 0.45, 2.12, -2.35, "orange")
    return tracks, outs


def compact_leader(m):
    """Box leader pinned at its foot; two backstay rams from the upper structure raise it and lay it
    back onto the rest for travel. Returns the backstay joints."""
    M = "$mast"
    box_leader(m, M, 0.3, 2.25, 2.85, 1.25, S_TOP - 0.3)
    cbox(m, M, -0.38, 1.25, 2.1, 0.38, 1.8, 2.95, "orange", c=0.04)
    cyl(m, M, S_MAST + [-0.5, 0, 0], S_MAST + [0.5, 0, 0], 0.1, n=8, tag="steel")
    cbox(m, M, -0.42, S_TOP - 0.3, 2.1, 0.42, S_TOP + 0.1, 3.6, "orange", c=0.04)
    for z in (2.4, 3.35):
        cyl(m, M, (-0.1, S_TOP + 0.15, z), (0.1, S_TOP + 0.15, z), 0.24, n=12, tag="steel")
    lamp(m, M, 0.0, S_TOP - 0.1, 3.62, 0.07)
    # backstays: from brackets on the upper structure to about half the leader height, so the leader is
    # held near the middle (three-point support); they telescope to lay the leader back
    # one thick raising cylinder on the centre line, from a bracket on the deck to a lug on the back of
    # the leader (mounted so it keeps leverage about the foot pin all the way down)
    att = np.array([0.0, S_STAY_Y, 2.2])
    box(m, M, -0.16, S_STAY_Y - 0.22, 2.06, 0.16, S_STAY_Y + 0.22, 2.3, "orange")
    base = np.array([0.0, 1.55, 0.2])
    cbox(m, "$upper", -0.25, 1.33, -0.05, 0.25, 1.7, 0.45, "paint2", c=0.03)
    joints, e = actuator(m, "backstay", "$upper", base, att, "$mast", S_MAST, (1, 0, 0), 0, 1, 0.22, factor=80.0,
                         offset=-80.0)
    return joints, [e]


def compact_joints(extra):
    return ([J("$upper", S_SWING, (0, 1, 0), channel="swing"),
             J("$mast", S_MAST, (1, 0, 0), channel="mast", factor=80.0, offset=-80.0, parent="$upper"),
             ] + extra)


def compact_spec(joints):
    return {
        "top": {**cab_rider(*S_CAB, S_SEAT, 0.12)[2], "width": 3.2, "height": 3.0},
        "construction": {"joints": joints, "work_points": {"pile_point": pt("$mast", S_PILE)},
                         "seat_parts": [{"seat": 0, "part": "$upper"}], "swing_sound": dict(SWING_ALARM)},
    }


def hammer():
    """Drop hammer rig: the same pile handling as the rotary rig (pile length, setting, grip / let go,
    joining, follower). The helmet (cap) slides on the leader by "head" and sits on the pile; the
    hammer rests on it and is lifted off it by "hammer"; every blow drives the pile a little."""
    m = model("pile_driver_hammer_detail")
    tracks, outs = compact_base(m)
    stays, errs = compact_leader(m)
    z = S_PILE[2]
    hy = 5.4                                     # helmet top (grip) as drawn
    Hm, H = "$helmet", "$hammer"
    m.add(Hm, mech.cbox(0.0, hy - 0.15, z, 0.7, 0.3, 0.7, tag="paint2", c=0.06))           # helmet
    cyl(m, Hm, (0, hy - 0.34, z), (0, hy - 0.28, z), 0.3, n=14, tag="black")             # cushion
    cyl(m, Hm, (0, hy - 0.3, z), (0, hy - 0.26, z), 0.33, n=14, tag="paint2")
    box(m, Hm, -0.28, hy - 0.28, 2.88, 0.28, hy - 0.02, 3.03, "steel")                    # guide shoes
    m.add(H, mech.cbox(0.0, hy + 0.8, z, 0.8, 1.6, 0.8, tag="paint2", c=0.1))
    box(m, H, -0.3, hy + 0.05, 2.88, 0.3, hy + 1.55, 3.0, "steel")                        # guide shoes
    cyl(m, H, (0, hy + 1.58, z), (0, hy + 1.8, z), 0.14, n=8, tag="steel")              # lifting eye
    box(m, H, -0.41, hy + 0.95, z + 0.39, 0.41, hy + 1.15, z + 0.41, "stripe")
    rig = rotary_rig(m, "$mast", z, hy, 0.25, 0.3, "follower", 3.5, head=Hm, wing=False, hide_in=hy - 0.15)
    rig.append(J(H, (0, 0, 0), (0, 1, 0), mode="slide", channel="hammer", parent=Hm))
    stead = steady(m, "$mast", z, 0.42, 0.12, 1.3, 1.55, (0.2, 2.7))
    joints = compact_joints(outs + rig + stead + stays)
    spec = compact_spec(joints)
    # piles 2-9 blocks; helmet 1.7-9.3 above the ground; 3.5-block follower; hammer lifted 1.8
    spec["settings"] = {"pile_driver": {"pile_block": "minecraft:iron_block", "steady_top": 1.55,
                                        "steady_margin": 1.2, "steady_speed": 0.06, "max_depth": 40,
                                        "lift_height": 1.8, "ticks_per_block": 40.0,
                                        "pile_length_min": 2, "pile_length_max": 9,
                                        "default_pile_length": 6, "head_min": 1.7, "head_max": 9.3,
                                        "head_speed": 0.12, "cap_offset": 0.3, "tool": "follower",
                                        "tool_length": 3.5, "tool_speed": 0.05, "load_ticks_per_block": 5,
                                        "extract_factor": 0.6, "grip_tolerance": 0.6}}
    merge_crawlers(spec, tracks)
    return m, spec, tuple(errs)


def rotary():
    m = model("pile_driver_rotary_detail")
    tracks, outs = compact_base(m)
    stays, errs = compact_leader(m)
    R = "$rotary_head"
    z = S_PILE[2]
    hy = 5.4                                     # rotary head height on the leader
    cbox(m, R, -0.5, hy, 2.95, 0.5, hy + 1.0, 3.85, "paint2", c=0.07)
    cbox(m, R, -0.4, hy + 1.0, 3.0, 0.4, hy + 1.5, 3.75, "orange", c=0.05)
    for s in (1, -1):
        cyl(m, R, (s * 0.3, hy + 0.5, z), (s * 0.62, hy + 0.5, z), 0.18, n=10, tag="steel")
    box(m, R, -0.28, hy + 0.05, 2.88, 0.28, hy + 1.35, 2.98, "steel")
    for x in (-0.12, 0.12):
        tube(m, R, [(x, hy + 1.5, 3.3), (x, hy + 2.0, 3.1), (x, hy + 3.0, 2.92)], 0.035, tag="black", n=5)
    # pile guide (steady) at the leader foot, fixed to the leader; the pile hangs from the rotary head
    stead = steady(m, "$mast", z, 0.42, 0.12, 1.3, 1.55, (0.2, 2.7))
    # the drive rod runs through the head; its foot is the cap on the pile head
    rig = rotary_rig(m, "$mast", z, hy, 0.25, 0.25, "drive_rod", 3.5)
    joints = compact_joints(outs + rig + stead + stays)
    spec = compact_spec(joints)
    # piles 2-9 blocks; head grip 1.7-9.3 above the ground (guide below, crown above); 3.5-block drive rod
    spec["settings"] = {"pile_driver": {"pile_block": "minecraft:iron_block", "steady_top": 1.55,
                                        "steady_margin": 1.2, "steady_speed": 0.06, "max_depth": 48, "pile_length_min": 2, "pile_length_max": 9,
                                        "default_pile_length": 6, "head_min": 1.7, "head_max": 9.3,
                                        "head_speed": 0.12, "cap_offset": 0.25, "tool": "drive_rod",
                                        "tool_length": 3.5, "tool_speed": 0.05, "load_ticks_per_block": 5,
                                        "extract_factor": 0.6, "grip_tolerance": 0.6}}
    merge_crawlers(spec, tracks)
    return m, spec, tuple(errs)


# ====================================================================== large three-point rig
L_SWING = np.array([0.0, 1.25, 0.0])
L_FOOT = np.array([0.0, 1.6, 3.4])     # leader foot pin (leader axis)
L_DOWN = 72.0                          # leader lowered back by this angle onto the rest
L_STAY_Y = 12.5                        # backstay attachment height on the leader
L_PILE_Z = 4.5
L_PILE = np.array([0.0, 1.4, L_PILE_Z])
L_SEAT = (1.1, 1.7, 1.4)
L_CAB = (0.6, 1.6, 1.65, 3.4, 0.6, 2.2)
L_TOP = 24.0
TRACK_EXT = 0.7


def large():
    m = model("pile_driver_large_detail")
    cbox(m, "body", -0.9, 0.55, -2.6, 0.9, 1.2, 2.6, "paint2", c=0.08)
    cyl(m, "body", (0, 1.12, 0), (0, 1.27, 0), 0.95, n=24, tag="paint2")
    for z in (-1.8, 1.8):
        box(m, "body", -0.94, 0.6, z - 0.35, 0.94, 1.1, z + 0.35, "paint")
    joints, tracks = [], []
    for side, s in (("l", 1), ("r", -1)):
        g = "$track_" + side
        # axle beam reaching into the carbody (still overlapping it when widened)
        for z in (-1.8, 1.8):
            box(m, g, s * 0.2, 0.68, z - 0.25, s * 1.05, 1.02, z + 0.25, "paint")
        joints.append(J(g, (0, 0, 0), (s, 0, 0), mode="slide", channel="track_width", factor=TRACK_EXT))
        # the whole crawler (shoes and rollers too) rides on the widening frame, so this addon draws it
        tracks.append(crawler(m, side, s * 0.95, s * 1.75, 3.2, 1.15, r_end=0.49, wheel_r=0.18, n_wheels=8,
                              carrier_r=0.12, n_carrier=2, link_len=0.24, shoe_t=0.08, addon_parent=g))
    upper_structure(m, 1.65, 2.4, 3.8, (1.25, 1.65), 3.0, 2.9, L_CAB, L_SEAT)
    U = "$upper"
    # outriggers under the corners of the upper structure, out diagonally past the widened crawlers
    joints += outriggers(m, corner_mounts(2.4, tail_point(1.65, 3.8, 225), 1.65, 0.22), 1.36, 0.18, 1.7, 1.2,
                         0.36, 2.9, 3.0)
    cyl(m, U, (-1.3, 2.0, 1.5), (0.3, 2.0, 1.5), 0.3, n=14, tag="rope")
    cyl(m, U, (-1.3, 2.0, 2.15), (0.3, 2.0, 2.15), 0.3, n=14, tag="rope")
    cbox(m, U, -0.7, 1.25, 2.3, 0.7, 2.3, 3.2, "paint", c=0.06)
    # rest for the lowered leader, on the counterweight
    rest_z = -2.6
    lean = math.radians(90.0 - L_DOWN)
    rest_y = L_FOOT[1] + (L_FOOT[2] - rest_z) * math.tan(lean) - 0.4 / math.cos(lean)
    for x in (-0.45, 0.45):
        bar(m, U, (x, 2.95, rest_z), (x * 0.8, rest_y - 0.02, rest_z), 0.14, 0.14, "orange", up=(0, 0, 1))
    box(m, U, -0.55, rest_y - 0.12, rest_z - 0.2, 0.55, rest_y, rest_z + 0.2, "orange")
    # leader: round pipe in sections, pinned at its foot to the bracket; three-point support with the
    # two backstays
    Ld = "$leader"
    cz = L_FOOT[2]
    pipe_leader(m, Ld, 0.4, cz, 1.35, L_TOP - 0.3)
    cbox(m, Ld, -0.55, 1.3, cz - 0.55, 0.55, 1.9, cz + 0.55, "orange", c=0.06)
    cyl(m, Ld, L_FOOT + [-0.65, 0, 0], L_FOOT + [0.65, 0, 0], 0.11, n=8, tag="steel")
    cbox(m, Ld, -0.6, L_TOP - 0.4, cz - 0.6, 0.6, L_TOP + 0.05, cz + 1.4, "orange", c=0.05)
    for z in (cz, cz + 1.1):
        cyl(m, Ld, (-0.12, L_TOP + 0.2, z), (0.12, L_TOP + 0.2, z), 0.3, n=12, tag="steel")
    lamp(m, Ld, 0.0, L_TOP - 0.2, cz + 1.42, 0.08)
    stead = steady(m, Ld, L_PILE_Z, 0.62, 0.15, 1.35, 1.65, (0.22, 3.6))
    # backstays to about half the leader height (three-point support); telescopic to lay it back
    stays, errs = [], []
    for s, name in ((1, "backstay_l"), (-1, "backstay_r")):
        att = np.array([s * 0.5, L_STAY_Y, cz - 0.42])
        box(m, Ld, s * 0.3, L_STAY_Y - 0.3, cz - 0.58, s * 0.62, L_STAY_Y + 0.3, cz - 0.3, "orange")
        j, e = actuator(m, name, U, np.array([s * 0.8, 3.1, -3.0]), att, "$leader", L_FOOT, (1, 0, 0), 0, 1, 0.17,
                        factor=L_DOWN, offset=-L_DOWN)
        stays += j; errs.append(e)
        box(m, U, s * 0.62, 2.95, -3.25, s * 0.98, 3.2, -2.75, "paint2")
    # rotary head high on the leader gripping the top of the steel pipe pile
    R = "$rotary_head"
    hy = 10.0
    cbox(m, R, -0.75, hy, 3.95, 0.75, hy + 1.6, 5.05, "paint2", c=0.1)
    cbox(m, R, -0.6, hy + 1.6, 4.05, 0.6, hy + 2.3, 4.95, "orange", c=0.07)
    box(m, R, -0.4, hy + 0.1, 3.82, 0.4, hy + 2.0, 3.96, "steel")
    for s in (1, -1):
        cyl(m, R, (s * 0.5, hy + 0.9, L_PILE_Z), (s * 0.95, hy + 0.9, L_PILE_Z), 0.25, n=10, tag="steel")
    for x in (-0.15, 0.15):
        tube(m, R, [(x, hy + 2.3, 4.4), (x, hy + 3.0, 4.1), (x, hy + 4.5, 3.9)], 0.045, tag="black", n=5)
    rig = rotary_rig(m, Ld, L_PILE_Z, hy, 0.4, 0.2, "follower", 6.0)

    joints += [
        J(U, L_SWING, (0, 1, 0), channel="swing"),
        J(Ld, L_FOOT, (1, 0, 0), channel="mast", factor=L_DOWN, offset=-L_DOWN, parent=U),
    ] + rig + stead + stays
    spec = {
        "top": {**cab_rider(*L_CAB, L_SEAT, 0.12)[2], "width": 3.5, "height": 3.4},
        "construction": {"joints": joints, "work_points": {"pile_point": pt(Ld, L_PILE)},
                         "seat_parts": [{"seat": 0, "part": U}], "swing_sound": dict(SWING_ALARM)},
        # piles 4-18 blocks; head grip 1.8-19 above the ground (guide below, crown above); 6-block follower
        "settings": {"pile_driver": {"pile_block": "minecraft:iron_block", "steady_top": 1.65, "steady_margin": 1.2,
                                     "steady_speed": 0.05, "max_depth": 80, "pile_length_min": 4, "pile_length_max": 18,
                                     "default_pile_length": 12, "head_min": 1.8, "head_max": 19.0,
                                     "head_speed": 0.15, "cap_offset": 0.2, "tool": "follower", "tool_length": 6.0,
                                     "tool_speed": 0.03, "load_ticks_per_block": 4, "extract_factor": 0.6,
                                     "grip_tolerance": 0.6}},
    }
    merge_crawlers(spec, tracks)
    return m, spec, tuple(errs)
