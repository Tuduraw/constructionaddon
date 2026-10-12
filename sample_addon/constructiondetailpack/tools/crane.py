"""25 t class rough terrain crane (original design).

Class reference (general published ranges, compared for structure and size only): overall length ~11 m,
width ~2.6 m, height ~3.5 m, wheelbase ~3.6 m, 2 axles with all-wheel steering, tyres ~1.3 m,
4-section boom ~9-30 m, outrigger span ~6.3 m, cab beside the boom on the carrier front.
"""
import numpy as np

from common import (cab_rider, box, cbox, cyl, side_plate, plan_plate, tube, lamp, cab, wheel, actuator, model, mk, J, pt,
                    seat, wheel_parts)

SWING = np.array([0.0, 1.6, -0.6])
LUFF = np.array([0.0, 2.55, -3.0])
TIP = np.array([0.0, 2.35, 7.2])
SEAT = (0.92, 1.75, 0.35)
CAB = (0.55, 1.3, 1.75, 3.25, -0.4, 1.3)
WB = 1.85            # half wheelbase
TR = 0.98            # half track
WR = 0.65            # tyre radius
OUT_Z = (3.35, -3.45)
OUT_TRAVEL = 2.1
EXTEND = 21.0        # total telescoping (3 sections)


def crane():
    m = model("crane_25t_detail")
    # ---------------------------------------------------------------- carrier
    side_plate(m, "body", [(-3.9, 0.72), (4.3, 0.72), (4.45, 1.0), (4.3, 1.38), (-3.9, 1.38), (-4.05, 1.0)],
               -0.55, 0.55, "paint2")
    for z0, z1 in ((-WB + 0.85, WB - 0.85), (WB + 0.85, 4.35), (-3.95, -WB - 0.85)):
        box(m, "body", -1.31, 1.3, z0, 1.31, 1.6, z1, "paint")
    for zc in (WB, -WB):
        for s in (1, -1):
            side_plate(m, "body", [(zc - 0.9, 1.38), (zc + 0.9, 1.38), (zc + 0.75, 1.6), (zc - 0.75, 1.6)],
                       s * 0.55, s * 1.31, "paint")
            box(m, "body", s * 0.55, 0.9, zc - 0.85, s * 0.62, 1.42, zc + 0.85, "paint2")
        box(m, "body", -0.55, 1.35, zc - 0.85, 0.55, 1.6, zc + 0.85, "paint")
        cyl(m, "body", (-0.75, WR, zc), (0.75, WR, zc), 0.13, n=8, tag="paint2")     # axle
        box(m, "body", -0.3, WR - 0.12, zc - 0.25, 0.3, WR + 0.2, zc + 0.25, "paint2")
    for z in OUT_Z:
        box(m, "body", -1.3, 0.8, z - 0.32, 1.3, 1.32, z + 0.32, "paint2")
    for z, f in ((4.45, 1), (-4.05, -1)):
        box(m, "body", -1.25, 0.62, z - 0.1, 1.25, 0.98, z + 0.06, "stripe")
        for x in (0.95, -0.95):
            lamp(m, "body", x, 1.15, z + f * 0.02, 0.09, facing=(0, 0, f), tag="lamp" if f > 0 else "tail")
    for s in (1, -1):
        cbox(m, "body", s * 1.0, 0.75, -0.5, s * 1.3, 1.3, 0.7, "paint2", c=0.04)        # tool / fuel box
        for y in (0.55, 0.95):
            box(m, "body", s * 1.15, y, 0.75, s * 1.3, y + 0.04, 1.05, "steel")
    plan_plate(m, "body", [(0, 1.1), (1.1, 0), (0, -1.1), (-1.1, 0)], 1.58, 1.62, "steel")
    cyl(m, "body", SWING + [0, -0.05, 0], SWING + [0, 0.06, 0], 1.12, n=24, tag="paint2")
    wheels = []
    for i, (x, z) in enumerate([(TR, WB), (-TR, WB), (TR, -WB), (-TR, -WB)]):
        wheel(m, "$wheel%d" % i, x, WR, z, WR, 0.6)
        wheels.append(("$wheel%d" % i, (x, WR, z), 25.0))
    for side, s in (("l", 1), ("r", -1)):
        g = "$outrigger_" + side
        for z in OUT_Z:
            # telescopic beam: long enough to stay well inside the outrigger box when fully out; left and
            # right beams side by side (staggered fore and aft) so they pass each other inside the box
            dz = s * 0.13
            box(m, g, s * -1.25, 0.88, z + dz - 0.11, s * 1.1, 1.24, z + dz + 0.11, "paint")
            cbox(m, g, s * 0.92, 0.3, z - 0.2, s * 1.2, 1.4, z + 0.2, "paint", c=0.03)
            cyl(m, g, (s * 1.06, 0.1, z), (s * 1.06, 0.34, z), 0.1, n=8, tag="chrome")
            cyl(m, g, (s * 1.06, 0.0, z), (s * 1.06, 0.1, z), 0.24, n=14, tag="paint2")

    # ---------------------------------------------------------------- upper
    U = "$upper"
    plan_plate(m, U, [(1.2, 0.9), (-1.2, 0.9), (-1.2, -3.5), (-0.95, -3.85), (0.95, -3.85), (1.2, -3.5)], 1.6, 1.9,
               "paint2")
    cbox(m, U, -1.2, 1.8, -4.05, 1.2, 2.6, -3.35, "paint", c=0.08)                   # counterweight
    box(m, U, -1.18, 1.85, -4.07, 1.18, 2.05, -4.03, "stripe")
    side_plate(m, U, [(-3.3, 1.88), (-0.7, 1.88), (-0.7, 2.35), (-1.0, 2.6), (-3.3, 2.6)], -1.2, -0.45, "paint")
    for i in range(8):
        z = -3.1 + i * 0.28
        box(m, U, -1.22, 2.0, z, -1.19, 2.45, z + 0.14, "grille")
    for x in (-0.44, 0.44):                                                          # boom foot / winch frames
        side_plate(m, U, [(-3.45, 1.88), (-2.0, 1.88), (-2.0, 2.4), (-2.7, 2.85), (-3.45, 2.75)], x - 0.06,
                   x + 0.06, "paint")
    cyl(m, U, (-0.38, 2.25, -2.35), (0.38, 2.25, -2.35), 0.24, n=14, tag="rope")
    cyl(m, U, (-0.6, LUFF[1], LUFF[2]), (0.6, LUFF[1], LUFF[2]), 0.1, n=8, tag="steel")
    cab(m, U, *CAB, door_side=1, seat=SEAT, front_slope=0.18)
    lamp(m, U, 0.92, 3.33, 1.25, 0.07)
    tube(m, U, [(-1.15, 2.6, -3.2), (-1.15, 2.95, -3.1), (-1.15, 2.95, -1.0), (-1.15, 2.6, -0.85)], 0.03)
    cyl(m, U, (-0.95, 2.55, -1.3), (-0.95, 3.05, -1.3), 0.06, n=8, tag="black")

    # ---------------------------------------------------------------- telescopic boom (4 sections)
    def section(part, hw, y0, y1, z0, z1, tag):
        m.add(part, mk.loft([np.array([(hw, y0, z), (-hw, y0, z), (-hw * 0.8, y1, z), (hw * 0.8, y1, z)])
                             for z in (z0, z1)], tag=tag))
        box(m, part, -hw - 0.01, y0 - 0.01, z1 - 0.18, hw + 0.01, y1 + 0.01, z1, "paint2")
    section("$boom", 0.4, 2.4, 3.25, -3.45, 5.4, "paint")
    for x in (-0.46, 0.38):
        side_plate(m, "$boom", [(-3.45, 2.4), (-2.3, 2.4), (-2.3, 3.25), (-3.3, 3.2)], x, x + 0.08, "paint2")
    section("$boom2", 0.33, 2.46, 3.18, -2.4, 6.0, "white")
    section("$boom3", 0.27, 2.52, 3.12, -1.6, 6.5, "paint")
    section("$boom4", 0.21, 2.58, 3.06, -0.9, 6.95, "white")
    B4 = "$boom4"
    box(m, B4, -0.26, 2.5, 6.9, 0.26, 3.1, 7.05, "paint2")
    for x in (-0.27, 0.21):
        side_plate(m, B4, [(6.9, 2.5), (7.25, 2.2), (7.45, 2.4), (7.4, 3.0), (6.9, 3.12)], x, x + 0.06, "paint2")
    for x in (-0.14, 0.0, 0.14):
        cyl(m, B4, (x - 0.05, 2.62, 7.2), (x + 0.05, 2.62, 7.2), 0.28, n=14, tag="steel")
    lamp(m, B4, 0.0, 3.15, 6.95, 0.06)
    # luffing ram
    j_ram, err = actuator(m, "luff_ram", U, np.array([0.0, 1.88, -1.0]), np.array([0.0, 2.4, 2.8]), "$boom",
                          LUFF, (-1, 0, 0), 0, 78, 0.2, barrel_frac=0.55)
    cbox(m, U, -0.3, 1.66, -1.25, 0.3, 1.95, -0.75, "paint2", c=0.03)
    # rope and hook block
    for dx in (-0.07, 0.07):
        cyl(m, "$rope", TIP + [dx, -1.0, 0], TIP + [dx, 0, 0], 0.025, n=5, tag="rope")
    H = "$hook"
    cbox(m, H, -0.24, TIP[1] - 0.7, TIP[2] - 0.2, 0.24, TIP[1] - 0.05, TIP[2] + 0.2, "orange", c=0.05)
    cyl(m, H, (-0.25, TIP[1] - 0.32, TIP[2]), (0.25, TIP[1] - 0.32, TIP[2]), 0.17, n=12, tag="steel")
    cyl(m, H, TIP + [0, -0.95, 0], TIP + [0, -0.68, 0], 0.06, n=6, tag="steel")
    tube(m, H, [TIP + [0, -0.95, 0], TIP + [0, -1.1, 0.08], TIP + [0, -1.12, 0.2], TIP + [0, -1.0, 0.27]], 0.045)

    joints = [
        J("$outrigger_l", (0, 0, 0), (1, 0, 0), mode="slide", channel="outrigger", factor=OUT_TRAVEL),
        J("$outrigger_r", (0, 0, 0), (-1, 0, 0), mode="slide", channel="outrigger", factor=OUT_TRAVEL),
        J(U, SWING, (0, 1, 0), channel="swing"),
        J("$boom", LUFF, (-1, 0, 0), channel="luff", parent=U),
        J("$boom2", LUFF, (0, 0, 1), mode="slide", channel="extend", factor=round(1 / 3, 4), parent="$boom"),
        J("$boom3", LUFF, (0, 0, 1), mode="slide", channel="extend", factor=round(1 / 3, 4), parent="$boom2"),
        J("$boom4", LUFF, (0, 0, 1), mode="slide", channel="extend", factor=round(1 / 3, 4), parent="$boom3"),
        J("$rope", TIP, (0, 1, 0), mode="scale", channel="rope", parent=B4, offset=0.0, inherit_rotation=False),
        J("$hook", TIP, (0, -1, 0), mode="slide", channel="rope", parent=B4, inherit_rotation=False),
    ] + j_ram
    spec = {
        "top": {**cab_rider(*CAB, SEAT, 0.18)[2], "wheel_parts": wheel_parts(wheels), "width": 3.0,
                "height": 3.5},
        "construction": {"joints": joints, "work_points": {"boom_tip": pt(B4, TIP)},
                         "seat_parts": [{"seat": 0, "part": U}]},
        "settings": {"crane": {"extend_max": EXTEND, "extend_speed": 0.08, "rope_max": 50.0, "hook_drop": 1.15,
                               "max_lift_width": 6.0, "outrigger_speed": 0.03}},
    }
    return m, spec, (err,)
