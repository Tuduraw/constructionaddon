"""3 t class spider (mini crawler) crane (original design).

Class reference (general published ranges, compared for structure and size only): width ~0.75-0.8 m (fits a
door), length ~2.6-2.8 m stowed, height ~1.5 m, rubber crawlers, 4-5 section telescopic boom to ~8-9 m, four
outrigger legs that swing out from the corners to a footprint of ~4 x 4 m, hook block on the boom head.
"""
import math

import numpy as np

from common import (box, cbox, cyl, bar, side_plate, tube, lamp, actuator, crawler, merge_crawlers, model, J, pt,
                    open_scale, open_seat, riders_json, unit)

SWING = np.array([0.0, 0.5, -0.1])
LUFF = np.array([0.0, 1.06, -0.5])
TIP = np.array([0.0, 0.98, 1.52])
EXTEND = 4.5            # 4 telescopic sections
SWING_ALARM = {"sound": "minecraft:block.note_block.bit", "volume": 0.6, "pitch": 1.8, "interval": 8}


def spider_crane():
    m = model("spider_crane_3t_detail")
    tracks = []
    for s in (1, -1):
        tracks.append(crawler(m, "l" if s > 0 else "r", s * 0.16, s * 0.385, 0.95, 0.34, r_end=0.13, wheel_r=0.06,
                              n_wheels=5, link_len=0.12, shoe_t=0.05))
    # ---------------------------------------------------------------- carrier
    cbox(m, "body", -0.33, 0.2, -0.9, 0.33, 0.48, 0.9, "paint2", c=0.04)
    cyl(m, "body", SWING + [0, -0.04, 0], SWING + [0, 0.04, 0], 0.32, n=16, tag="steel")
    # ---------------------------------------------------------------- legs
    # Modelled deployed (straight out on the diagonal, pads on the ground). "outrigger" 0..0.5 turns them
    # up 90 deg about the horizontal axis across each leg, so they stand upright beside the body for travel;
    # 0.5..1 lowers the pads.
    joints = []
    for name, sx, sz, zh in (("leg_fl", 1, 1, 0.72), ("leg_fr", -1, 1, 0.72), ("leg_rl", 1, -1, -0.62),
                             ("leg_rr", -1, -1, -0.62)):
        g, foot = "$" + name, "$%s_foot" % name
        H = np.array([sx * 0.42, 0.48, zh])
        d = unit(np.array([sx * 0.6, 0.0, sz * 0.8]))
        T = H + d * 1.25
        cbox(m, "body", sx * 0.33, 0.36, zh - 0.08, sx * 0.47, 0.58, zh + 0.08, "paint2", c=0.02)  # hinge bracket
        cyl(m, "body", H + [0, 0, -0.07], H + [0, 0, 0.07], 0.045, n=6, tag="steel")
        bar(m, g, H, T, 0.08, 0.11, "paint")
        bar(m, g, H + d * 0.15 + [0, 0.07, 0], T + [0, 0.07, 0], 0.05, 0.03, "paint2")             # top flange
        cyl(m, g, T + [0, 0.02, 0], T + [0, 0.24, 0], 0.06, n=8, tag="paint")                    # jack cylinder
        cyl(m, foot, T + [0, -0.4, 0], T + [0, 0.12, 0], 0.03, n=6, tag="chrome")
        cyl(m, foot, T + [0, -0.48, 0], T + [0, -0.42, 0], 0.13, n=10, tag="black")               # pad
        axis = unit(np.cross(d, (0, 1, 0)))
        joints.append(J(g, H, axis, channel="outrigger", factor=-180.0, offset=90.0, min=0.0, max=90.0))
        joints.append(J(foot, (0, 0, 0), (0, 1, 0), mode="slide", channel="outrigger", parent=g,
                        factor=-0.2, offset=0.2, min=0.0, max=0.1))
    # ---------------------------------------------------------------- upper structure
    U = "$upper"
    cbox(m, U, -0.36, 0.54, -0.85, 0.36, 0.78, 0.55, "paint", c=0.04)
    cbox(m, U, -0.36, 0.78, -0.85, 0.36, 0.95, -0.45, "paint", c=0.05)                       # engine / counterweight
    for x in np.linspace(-0.28, 0.28, 6):
        box(m, U, x - 0.02, 0.82, -0.86, x + 0.02, 0.92, -0.855, "grille")
    side_plate(m, U, [(-0.65, 0.78), (-0.3, 0.78), (-0.35, 1.12), (-0.62, 1.12)], -0.15, 0.15, "paint2")  # boom foot
    cbox(m, U, -0.1, 0.55, -0.42, 0.1, 0.8, -0.18, "paint2", c=0.02)                         # ram foot
    lamp(m, U, 0.3, 0.97, -0.6, 0.04, facing=(0, 1, 0), tag="orange")                         # beacon
    # operator step and seat at the back (the rider's legs reach in over the engine cover)
    cbox(m, U, -0.36, 0.5, -1.35, 0.36, 0.55, -0.85, "steel", c=0.01)
    for sx in (1, -1):
        bar(m, U, (sx * 0.33, 0.55, -0.88), (sx * 0.33, 0.55, -1.32), 0.03, 0.05, "paint2")
    s = open_scale(-0.34, 0.34, -1.33, -0.35)
    st = open_seat(m, U, 0.55, -0.34, 0.34, -1.33, -0.35, 0.0, s, "operator", True, eye_over=1.05)
    cbox(m, U, -0.3, 0.96, -0.55, -0.12, 1.12, -0.42, "paint2", c=0.02)                       # lever box
    for x in (-0.27, -0.22, -0.17):
        cyl(m, U, (x, 1.12, -0.5), (x, 1.24, -0.46), 0.01, n=4, tag="black")
    # ---------------------------------------------------------------- boom (5 sections) and hook
    secs = [("$boom1", 0.13, 0.13, -0.62, 1.22, "paint"), ("$boom2", 0.105, 0.105, -0.55, 1.28, "white"),
            ("$boom3", 0.085, 0.085, -0.5, 1.33, "paint"), ("$boom4", 0.065, 0.068, -0.45, 1.38, "white"),
            ("$boom5", 0.05, 0.052, -0.4, 1.43, "paint")]
    for g, hw, hh, z0, z1, tag in secs:
        box(m, g, -hw, LUFF[1] - hh, z0, hw, LUFF[1] + hh, z1, tag)
    B5 = "$boom5"
    cbox(m, B5, -0.07, 0.94, 1.38, 0.07, 1.12, 1.55, "paint2", c=0.02)                       # head
    cyl(m, B5, (-0.04, TIP[1] + 0.02, TIP[2]), (0.04, TIP[1] + 0.02, TIP[2]), 0.07, n=10, tag="steel")
    for dx in (-0.02, 0.02):
        cyl(m, "$rope", TIP + [dx, -1.0, 0], TIP + [dx, 0, 0], 0.01, n=4, tag="rope")
    H = "$hook"
    cbox(m, H, -0.07, TIP[1] - 0.28, TIP[2] - 0.07, 0.07, TIP[1] - 0.04, TIP[2] + 0.07, "orange", c=0.02)
    tube(m, H, [TIP + [0, -0.3, 0], TIP + [0, -0.4, 0.03], TIP + [0, -0.42, 0.09], TIP + [0, -0.36, 0.12]], 0.018)
    j_ram, err = actuator(m, "luff_ram", U, np.array([0.0, 0.62, -0.3]), np.array([0.0, 0.93, 0.35]), "$boom1",
                          LUFF, (-1, 0, 0), 0, 80, 0.06, tag="paint2")
    box(m, "$boom1", -0.06, 0.89, 0.25, 0.06, 0.94, 0.45, "paint2")
    joints += [
        J(U, SWING, (0, 1, 0), channel="swing"),
        J("$boom1", LUFF, (-1, 0, 0), channel="luff", parent=U),
    ]
    for (g, *_), (prev, *_) in zip(secs[1:], secs[:-1]):
        joints.append(J(g, (0, 0, 0), (0, 0, 1), mode="slide", channel="extend", factor=0.25, parent=prev))
    joints += [
        J("$rope", TIP, (0, 1, 0), mode="scale", channel="rope", parent=B5, offset=0.0, inherit_rotation=False),
        J(H, TIP, (0, -1, 0), mode="slide", channel="rope", parent=B5, inherit_rotation=False),
    ] + j_ram
    spec = {
        "top": {**riders_json([st], s), "width": 1.0, "height": 1.5},
        "construction": {"joints": joints, "work_points": {"boom_tip": pt(B5, TIP)},
                         "seat_parts": [{"seat": 0, "part": U}], "swing_sound": dict(SWING_ALARM)},
        "settings": {"crane": {"extend_max": EXTEND, "extend_speed": 0.035, "luff_max": 80.0, "rope_max": 25.0,
                               "rope_min": 0.3, "hook_drop": 0.45, "max_lift_width": 2.5, "outrigger_speed": 0.02}},
    }
    merge_crawlers(spec, tracks)
    return m, spec, (err,)
