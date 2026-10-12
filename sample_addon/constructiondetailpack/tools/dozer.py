"""20 t class crawler bulldozer (original design).

Class reference (general published ranges, compared for structure and size only): length ~5.5 m with
blade, blade width ~3.4 m and height ~1.3 m, track gauge ~2.0 m, shoe ~0.56 m, length of track on
ground ~2.9 m, height to the top of the cab ~3.2 m; single-shank ripper at the rear.
"""
import math

import numpy as np

from common import (cab_rider, box, cbox, cyl, bar, side_plate, lamp, cab, crawler, merge_crawlers, model, mk, mech, seat,
                    actuator)

SEAT = (0.0, 1.6, -0.95)
CAB = (-0.72, 0.72, 1.55, 3.05, -1.7, -0.2)
BLADE_Z = 3.0          # front face of the cutting edge
BLADE_W = 3.42
BLADE_H = 1.3
TRUNNION = np.array([0.0, 0.5, -0.3])   # push-arm pivot on the track frames
BLADE_MIN, BLADE_MAX = -1.0, 1.1
# degrees per block of cutting-edge height (radius trunnion -> edge)
BLADE_DEG = round(180.0 / (math.pi * math.hypot(TRUNNION[1], BLADE_Z - TRUNNION[2])), 3)


def bulldozer():
    m = model("bulldozer_20t_detail")
    tracks = []
    for s in (1, -1):
        tracks.append(crawler(m, "l" if s > 0 else "r", s * 0.71, s * 1.27, 1.65, 0.95, r_end=0.34, wheel_r=0.15,
                              n_wheels=7, carrier_r=0.1, n_carrier=1, link_len=0.2))
    # main frame, hood tapering forward, radiator guard
    cbox(m, "body", -0.7, 0.42, -2.05, 0.7, 1.35, 2.15, "paint2", c=0.06)
    m.add("body", mk.loft([np.array([(0.85, 1.3, z), (-0.85, 1.3, z), (-0.78, 1.95, z), (0.78, 1.95, z)])
                           for z in (-0.2,)] +
                          [np.array([(0.7, 1.3, 2.2), (-0.7, 1.3, 2.2), (-0.62, 1.85, 2.2), (0.62, 1.85, 2.2)])],
                          tag="paint"))
    box(m, "body", -0.64, 1.32, 2.2, 0.64, 1.82, 2.25, "grille")
    for i in range(6):
        y = 1.36 + i * 0.08
        box(m, "body", -0.66, y, 2.23, 0.66, y + 0.03, 2.29, "paint")
    for i in range(5):
        z = 0.1 + i * 0.35
        box(m, "body", -0.6, 1.94 - z * 0.04, z, 0.6, 1.96 - z * 0.04, z + 0.18, "grille")
    for x in (0.5, -0.5):
        lamp(m, "body", x, 1.98, 1.9, 0.08)
    # fenders / walkways over the tracks, fuel tank and tool box at the rear
    for s in (1, -1):
        box(m, "body", s * 0.68, 0.95, -2.05, s * 1.32, 1.02, 1.85, "paint")
        cbox(m, "body", s * 0.7, 1.02, -2.0, s * 1.15, 1.55, -0.3, "paint", c=0.04)
    cyl(m, "body", (0.45, 1.9, 1.1), (0.45, 2.85, 1.1), 0.065, n=8, tag="black")
    cyl(m, "body", (0.45, 2.85, 1.1), (0.45, 2.92, 1.18), 0.07, n=8, tag="black")
    cyl(m, "body", (-0.45, 1.9, 1.2), (-0.45, 2.35, 1.2), 0.13, n=10, tag="paint2")
    # cab and ROPS posts
    cab(m, "body", *CAB, door_side=1, seat=SEAT, front_slope=0.06)
    for x in (-0.8, 0.72):
        for z in (-1.8, -0.28):
            box(m, "body", x, 1.4, z, x + 0.08, 3.12, z + 0.1, "paint")
    cbox(m, "body", -0.85, 3.08, -1.85, 0.85, 3.2, -0.12, "paint", c=0.03)
    # ripper: beam, shank and tip on parallel links (static)
    box(m, "body", -0.75, 0.6, -2.35, 0.75, 1.0, -2.05, "paint2")
    for x in (-0.55, 0.55):
        bar(m, "body", (x, 0.95, -2.2), (x, 0.9, -2.9), 0.14, 0.18, "paint")
        bar(m, "body", (x, 0.65, -2.2), (x, 0.62, -2.85), 0.12, 0.14, "paint")
        cyl(m, "body", (x, 1.3, -2.1), (x, 1.05, -2.8), 0.08, n=6, tag="paint")
    box(m, "body", -0.7, 0.55, -3.05, 0.7, 1.05, -2.8, "paint")
    bar(m, "body", (0, 1.0, -2.95), (0, 0.15, -3.15), 0.12, 0.22, "paint2", up=(0, 0, 1))
    m.add("body", mech.claw((0, 0.2, -3.15), (0, -0.05, -3.05), 0.06, 0.05, "steel"))
    for s in (1, -1):
        lamp(m, "body", s * 0.9, 1.5, -2.05, 0.07, facing=(0, 0, -1))

    # blade: curved semi-U moldboard on push arms, lifted by two rams
    Bl = "$blade"
    hw = BLADE_W / 2
    rams = []
    arc = [(BLADE_Z, 0.0), (BLADE_Z - 0.25, 0.25), (BLADE_Z - 0.33, 0.6), (BLADE_Z - 0.28, 1.0),
           (BLADE_Z - 0.1, 1.25), (BLADE_Z, BLADE_H)]
    for (z0, y0), (z1, y1) in zip(arc, arc[1:]):
        bar(m, Bl, (0, y0, z0), (0, y1, z1), BLADE_W - 0.4, 0.08, "paint",
            up=np.cross([1, 0, 0], np.array([0, y1 - y0, z1 - z0])))
    for s in (1, -1):    # angled wings (semi-U)
        for (z0, y0), (z1, y1) in zip(arc, arc[1:]):
            a = np.array([s * (hw - 0.2), y0, z0]); b = np.array([s * (hw - 0.2), y1, z1])
            d = np.array([s * 0.2, 0, 0.22])
            m.add(Bl, mk.loft([np.array([a, b, b + [0, 0, -0.08], a + [0, 0, -0.08]]),
                               np.array([a + d, b + d, b + d + [0, 0, -0.08], a + d + [0, 0, -0.08]])],
                              tag="paint"))
        side_plate(m, Bl, [(BLADE_Z - 0.05, 0.0), (BLADE_Z + 0.24, 0.0), (BLADE_Z + 0.24, BLADE_H),
                           (BLADE_Z - 0.2, 1.1), (BLADE_Z - 0.35, 0.5)], s * hw - 0.04, s * hw + 0.04, "paint")
    box(m, Bl, -hw + 0.2, 0.0, BLADE_Z - 0.02, hw - 0.2, 0.12, BLADE_Z + 0.08, "steel")
    box(m, Bl, -hw + 0.4, 1.05, BLADE_Z - 0.45, hw - 0.4, 1.2, BLADE_Z - 0.25, "paint2")
    for s in (1, -1):
        bar(m, Bl, (s * 1.05, 0.5, -0.3), (s * 1.15, 0.5, BLADE_Z - 0.32), 0.2, 0.26, "paint")
        cyl(m, Bl, (s * 0.92, 0.5, -0.3), (s * 1.22, 0.5, -0.3), 0.13, n=8, tag="steel")
        bar(m, Bl, (s * 0.4, 0.75, BLADE_Z - 0.3), (s * 1.05, 0.55, 1.4), 0.1, 0.12, "paint2")
        # lift ram along the hood side: pinned on a bracket at the hood's rear corner, rod end on a lug on
        # the blade back (mounts chosen for a plain barrel + rod with good leverage about the trunnions)
        box(m, "body", s * 0.7, 1.85, 0.45, s * 1.0, 2.15, 0.75, "paint2")
        box(m, Bl, s * 0.8, 0.85, 2.45, s * 1.0, 1.15, BLADE_Z - 0.28, "paint2")
        j, e = actuator(m, "lift_ram_" + ("l" if s > 0 else "r"), None, np.array([s * 0.9, 2.05, 0.6]),
                        np.array([s * 0.9, 0.95, 2.6]), Bl, TRUNNION, (-1, 0, 0), BLADE_MIN, BLADE_MAX, 0.11,
                        factor=BLADE_DEG)
        rams += j
    m.add("$blade_load", mk.extrude_zy([(BLADE_Z, 0.0), (BLADE_Z + 0.8, 0.0), (BLADE_Z + 0.7, 0.5),
                                        (BLADE_Z + 0.3, 1.0), (BLADE_Z, 1.05)], -hw + 0.2, hw - 0.2, tag="earth"))
    joints = [
        # push arms turn about the trunnions; the cutting edge rises by "blade" blocks
        {"part": Bl, "pivot_x": 0, "pivot_y": float(TRUNNION[1]), "pivot_z": float(TRUNNION[2]), "axis_x": -1,
         "axis_y": 0, "axis_z": 0, "mode": "rotate", "channel": "blade", "factor": BLADE_DEG},
        {"part": "$blade_load", "parent": Bl, "pivot_x": 0, "pivot_y": 0, "pivot_z": BLADE_Z + 0.3, "axis_x": 0,
         "axis_y": 1, "axis_z": 0, "mode": "scale", "channel": "load", "offset": 0.0},
    ] + rams
    spec = {
        "top": {**cab_rider(*CAB, SEAT, 0.06)[2], "width": 3.0, "height": 3.2},
        "construction": {"joints": joints},
        "settings": {"bulldozer": {"blade_front_z": BLADE_Z + 0.05, "blade_width": BLADE_W, "blade_bottom_y": 0.0,
                                   "blade_height": BLADE_H, "blade_min": BLADE_MIN, "blade_max": BLADE_MAX,
                                   "capacity": 20}},
    }
    merge_crawlers(spec, tracks)
    return m, spec, ()
