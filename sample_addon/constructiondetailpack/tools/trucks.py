"""Road vehicles (original designs): 10 t class dump truck, concrete mixer truck, 6x4 tractor head and a
low-bed trailer for carrying the machines. Right-hand drive (the driver sits at -X), cab-over-engine.

Class reference (general published ranges, compared for structure and size only):
  Large dump truck: 3 axles (front steer + rear tandem), length ~7.6 m, width ~2.49 m, cab roof ~3.0 m,
    wheelbase ~4.7-5.0 m, body ~5.1 x 2.2 x 0.5 m, tyres ~1.05 m with rear duals.
  Mixer truck (~4.4 m3 drum): length ~7.7 m, height ~3.75 m, drum ~2.2 m in diameter, tilted ~13 deg.
  6x4 tractor: length ~6.3-6.6 m, fifth wheel ~1.25 m high.
  Low-bed trailer: length ~12-13 m, width ~3.0 m, deck ~0.8-0.9 m high, 2-axle bogie under a raised rear deck,
    ramps at the rear.
"""
import math

import numpy as np

from common import (fit_rider, rider_json, RIDER_SEAT, RIDER_FRONT, box, cbox, cyl, bar, side_plate, tube, lamp, wheel, actuator, model, mk, unit, J, pt, seat,
                    wheel_parts)

HW = 1.245          # half width (2.49 m)
TYRE_R = 0.53


def cabover(m, part, z0, z1, y_top, axle_z, tag="cabin", seat_x=-0.55, max_scale=1.0):
    """Cab-over-engine cab over the front axle. z0/z1: back/front of the cab. Returns the driver's
    'seats' / 'passenger_display' JSON, with the rider fitted inside the cab (common.fit_rider)."""
    wz0, wz1 = axle_z - TYRE_R - 0.08, axle_z + TYRE_R + 0.08
    yb = 1.35                      # bottom of the glasshouse part
    box(m, part, -0.8, 0.75, z0, 0.8, yb, z1 - 0.06, tag)
    box(m, part, -HW, 0.62, wz1, HW, yb, z1 - 0.06, tag)
    box(m, part, -HW, 0.75, z0, HW, yb, wz0, tag)
    for s in (1, -1):
        side_plate(m, part, [(wz0, 1.2), (wz1, 1.2), (wz1 - 0.1, yb + 0.02), (wz0 + 0.1, yb + 0.02)], s * 0.78,
                   s * HW, tag)
    slope = 0.22
    prof = [(z0, yb), (z1 - 0.02, yb), (z1, 1.95), (z1 - slope, y_top - 0.08), (z1 - slope - 0.1, y_top),
            (z0 + 0.06, y_top), (z0, y_top - 0.06)]
    side_plate(m, part, prof, -HW, HW, tag)
    wy0, wy1 = 2.0, y_top - 0.15
    f = lambda y: z1 - (y - 1.95) / (y_top - 0.08 - 1.95) * slope + 0.012
    za, zb = f(wy0), f(wy1)
    m.add(part, mk.loft([np.array([(HW - 0.1, wy0, za + d), (-HW + 0.1, wy0, za + d), (-HW + 0.1, wy1, zb + d),
                                   (HW - 0.1, wy1, zb + d)]) for d in (0.0, 0.02)], tag="glass"))
    door0 = z1 - 1.25
    for s in (1, -1):
        side_plate(m, part, [(door0 + 0.12, wy0), (z1 - 0.2, wy0), (zb - 0.1, wy1), (door0 + 0.12, wy1)],
                   s * HW, s * (HW + 0.012), "glass")
        box(m, part, s * HW, 0.9, door0, s * (HW + 0.008), y_top - 0.1, door0 + 0.03, "black")      # door seam
        box(m, part, s * HW, 1.75, door0 + 0.12, s * (HW + 0.02), 1.8, door0 + 0.28, "black")       # handle
        bar(m, part, (s * HW, 2.3, z1 - 0.3), (s * (HW + 0.32), 2.45, z1 - 0.05), 0.035, 0.035, "black")
        box(m, part, s * (HW + 0.26), 2.0, z1 - 0.1, s * (HW + 0.34), 2.6, z1 + 0.04, "black")     # mirror
        for y in (0.5, 0.92):                                                                     # steps
            box(m, part, s * 0.85, y, door0 + 0.05, s * (HW - 0.02), y + 0.05, door0 + 0.45, "steel")
        bar(m, part, (s * (HW + 0.02), 1.3, door0 - 0.02), (s * (HW + 0.02), 2.4, door0 - 0.02), 0.03, 0.03,
            "chrome")                                                                             # grab handle
    box(m, part, -HW, 0.45, z1 - 0.12, HW, 0.82, z1 + 0.1, "paint2")                               # bumper
    box(m, part, -0.7, 1.05, z1 - 0.07, 0.7, 1.75, z1 + 0.01, "grille")
    for i in range(5):
        y = 1.1 + i * 0.13
        box(m, part, -0.72, y, z1 - 0.02, 0.72, y + 0.04, z1 + 0.03, tag)
    for s in (1, -1):
        lamp(m, part, s * 0.95, 0.66, z1 + 0.1, 0.1)
        box(m, part, s * 1.05, 0.55, z1 + 0.09, s * 1.2, 0.62, z1 + 0.12, "orange")
        for x in (0.25, 0.6):
            lamp(m, part, s * x, y_top - 0.04, z1 - slope - 0.12, 0.04, facing=(0, 0.3, 1), tag="orange")
    # interior: floor, dashboard, steering wheel, seats
    box(m, part, -HW + 0.05, yb - 0.05, z0 + 0.03, HW - 0.05, yb, z1 - 0.1, "black")
    box(m, part, -HW + 0.06, yb, z1 - 0.5, HW - 0.06, yb + 0.35, z1 - 0.1, "black")
    slope_top = z1 - slope - 0.1
    (ox, oy, oz), k = fit_rider(-HW + 0.06, HW - 0.06, yb, y_top - 0.03, z0 + 0.08, slope_top - 0.04, seat_x,
                                max_scale)
    # forward until the knees reach the dashboard, the face staying behind the sloped windscreen
    oz = min(max(oz, z1 - 0.5 - RIDER_FRONT * k - 0.02), slope_top - 0.3 * k)
    cyl(m, part, (ox, 1.95, z1 - 0.55), (ox, 2.02, z1 - 0.62), 0.22, n=10, tag="black")
    cushion = oy + RIDER_SEAT * k
    back = oz - 0.13 * k
    for sx in (ox, -ox):
        cbox(m, part, sx - 0.25 * k, yb, back - 0.02, sx + 0.25 * k, cushion, oz + 0.35 * k, "seat", c=0.04)
        cbox(m, part, sx - 0.25 * k, cushion - 0.02, back - 0.12, sx + 0.25 * k, oy + 1.45 * k, back, "seat", c=0.04)
    return rider_json("driver", (ox, oy, oz), k)


def chassis(m, z0, z1, y=0.75):
    for s in (1, -1):
        box(m, "body", s * 0.36, y, z0, s * 0.5, y + 0.3, z1, "paint2")
    for z in np.linspace(z0 + 0.2, z1 - 0.2, 7):
        box(m, "body", -0.38, y + 0.04, z - 0.06, 0.38, y + 0.26, z + 0.06, "paint2")


def axles(m, zs, y=TYRE_R):
    for z in zs:
        cyl(m, "body", (-0.85, y, z), (0.85, y, z), 0.09, n=8, tag="paint2")
        box(m, "body", -0.3, y - 0.12, z - 0.18, 0.3, y + 0.18, z + 0.18, "paint2")
        for s in (1, -1):
            box(m, "body", s * 0.36, y + 0.09, z - 0.6, s * 0.5, y + 0.2, z + 0.6, "steel")


def truck_wheels(m, front_z, rear_zs, front_x=1.0, rear_x=0.93, r=TYRE_R, steer=30.0):
    out = []
    i = 0
    for x in (front_x, -front_x):
        wheel(m, "$wheel%d" % i, x, r, front_z, r, 0.3)
        out.append(("$wheel%d" % i, (x, r, front_z), steer)); i += 1
    for z in rear_zs:
        for x in (rear_x, -rear_x):
            wheel(m, "$wheel%d" % i, x, r, z, r, 0.62, dual=True)
            out.append(("$wheel%d" % i, (x, r, z), 0)); i += 1
    return wheel_parts(out)


def rear_end(m, z, fenders_z, y=1.12):
    for zc in fenders_z:
        for s in (1, -1):
            side_plate(m, "body", [(zc - 1.25, y), (zc + 1.25, y), (zc + 1.18, y + 0.06), (zc - 1.18, y + 0.06)],
                       s * 0.6, s * 1.25, "paint2")
    box(m, "body", -1.15, 0.75, z - 0.12, 1.15, 1.0, z, "stripe")
    for s in (1, -1):
        lamp(m, "body", s * 0.95, 0.88, z - 0.12, 0.07, facing=(0, 0, -1), tag="tail")
        box(m, "body", s * 0.7, 0.15, z + 0.3, s * 1.2, 0.8, z + 0.34, "black")                    # mud flap


def side_guard(m, z0, z1):
    for s in (1, -1):
        box(m, "body", s * 0.5, 0.75, z0 + 0.1, s * 1.12, 0.82, z0 + 0.2, "steel")
        box(m, "body", s * 0.5, 0.75, z1 - 0.2, s * 1.12, 0.82, z1 - 0.1, "steel")
        for y in (0.45, 0.75):
            box(m, "body", s * 1.1, y, z0, s * 1.16, y + 0.08, z1, "steel")


# ====================================================================== dump truck
D_FRONT, D_REAR = 3.0, (-1.3, -2.6)
D_HINGE = np.array([0.0, 1.25, -3.45])


def dump_truck():
    m = model("dump_truck_10t_detail")
    chassis(m, -3.55, 3.6)
    axles(m, (D_FRONT,) + D_REAR)
    wheels = truck_wheels(m, D_FRONT, D_REAR)
    st = cabover(m, "body", 2.2, 4.0, 3.0, D_FRONT, tag="orange")
    rear_end(m, -3.6, (-1.95,))
    side_guard(m, 0.3, 1.7)
    cbox(m, "body", -1.08, 0.5, 0.5, -0.55, 1.0, 1.75, "steel", c=0.07)                          # fuel tank
    cbox(m, "body", 0.55, 0.5, 0.9, 1.05, 0.95, 1.75, "paint2", c=0.04)                          # battery box
    tube(m, "body", [(1.0, 0.9, 2.15), (1.0, 1.6, 2.12), (1.0, 3.25, 2.05)], 0.055, tag="black", n=8)
    # body (tips about the rear hinge)
    Bd = "$bed"
    box(m, Bd, -1.15, 1.3, -3.5, 1.15, 1.45, 1.75, "orange")
    for s in (1, -1):
        side_plate(m, Bd, [(-3.5, 1.42), (1.75, 1.42), (1.75, 2.08), (-3.5, 2.05)], s * 1.06, s * 1.15, "orange")
        box(m, Bd, s * 1.13, 1.95, -3.5, s * 1.2, 2.1, 1.75, "orange")
        for z in np.linspace(-3.1, 1.3, 6):
            box(m, Bd, s * 1.13, 1.45, z - 0.05, s * 1.19, 2.0, z + 0.05, "orange")
        box(m, Bd, s * 1.13, 1.3, -3.5, s * 1.2, 1.45, 1.75, "paint2")
    box(m, Bd, -1.17, 1.42, 1.65, 1.17, 2.45, 1.75, "orange")                                    # front panel
    box(m, Bd, -1.2, 2.35, 1.65, 1.2, 2.45, 2.12, "orange")                                       # cab protector
    for x in np.linspace(-0.95, 0.95, 6):
        box(m, Bd, x - 0.03, 2.38, 1.75, x + 0.03, 2.44, 2.12, "paint2")
    box(m, Bd, -1.15, 1.45, -3.58, 1.15, 2.08, -3.48, "orange")                                   # tail gate
    box(m, Bd, -1.17, 1.98, -3.6, 1.17, 2.08, -3.46, "paint2")
    for x in (-0.48, 0.48):                                                                        # sub-frame
        box(m, Bd, x - 0.08, 1.12, -3.45, x + 0.08, 1.31, 1.6, "paint2")
    cyl(m, Bd, D_HINGE + [-0.6, 0, 0], D_HINGE + [0.6, 0, 0], 0.07, n=8, tag="steel")
    for x in (-0.48, 0.48):                                                                       # hinge brackets
        box(m, "body", x - 0.07, 0.95, D_HINGE[2] - 0.14, x + 0.07, D_HINGE[1] + 0.1, D_HINGE[2] + 0.14, "paint2")
    j_ram, err = actuator(m, "hoist_ram", None, np.array([0.0, 0.95, 1.3]), np.array([0.0, 1.15, -2.2]), "$bed",
                          D_HINGE, (-1, 0, 0), 0, 55, 0.13, tag="paint2", barrel_frac=0.5)
    m.add("$bed_load", mk.extrude_zy([(-3.4, 1.45), (1.6, 1.45), (1.6, 2.25), (0.8, 2.6), (-2.2, 2.55),
                                      (-3.4, 2.2)], -1.02, 1.02, tag="earth"))
    joints = [J(Bd, D_HINGE, (-1, 0, 0), channel="bed"),
              J("$bed_load", (0, 1.45, 0), (0, 1, 0), mode="scale", channel="load", parent=Bd, offset=0.0)] + j_ram
    spec = {
        "top": {**st, "wheel_parts": wheels, "width": 3.0, "height": 3.0},
        "construction": {"joints": joints, "work_points": {"discharge": pt(Bd, (0.0, 1.5, -3.85))}},
        "settings": {"dump_truck": {"bed_min_x": -1.05, "bed_min_y": 1.3, "bed_min_z": -3.45, "bed_max_x": 1.05,
                                    "bed_max_y": 3.6, "bed_max_z": 1.65}},
    }
    return m, spec, (err,)


# ====================================================================== mixer truck
M_FRONT, M_REAR = 3.0, (-1.2, -2.5)
DRUM_REAR = np.array([0.0, 3.03, -2.95])
DRUM_FRONT = np.array([0.0, 2.05, 1.4])
CHUTE = np.array([0.0, 2.35, -3.65])
CHUTE_TIP = np.array([0.0, 1.75, -5.05])


def mixer_truck():
    m = model("mixer_truck_detail")
    chassis(m, -3.7, 3.6)
    axles(m, (M_FRONT,) + M_REAR)
    wheels = truck_wheels(m, M_FRONT, M_REAR)
    st = cabover(m, "body", 2.2, 4.0, 3.0, M_FRONT, tag="white")
    rear_end(m, -3.75, (-1.85,))
    side_guard(m, 0.3, 1.7)
    cbox(m, "body", -1.08, 0.5, 0.5, -0.55, 1.0, 1.75, "steel", c=0.07)
    tube(m, "body", [(1.0, 0.9, 2.15), (1.0, 1.6, 2.12), (1.0, 3.25, 2.05)], 0.055, tag="black", n=8)
    ax = unit(DRUM_FRONT - DRUM_REAR); L = np.linalg.norm(DRUM_FRONT - DRUM_REAR)
    prof = [(-0.5, 0.5), (-0.35, 0.58), (0.0, 0.85), (0.38 * L, 1.1), (0.62 * L, 1.1), (L - 0.2, 0.7), (L, 0.35),
            (L + 0.06, 0.0)]
    m.add("$drum", mk.lathe(DRUM_REAR, ax, prof, n=18, tag="white"))
    side = unit(np.cross(ax, [1, 0, 0])); upv = np.cross(ax, side)
    for k in range(2):
        prev = None
        for i in range(29):
            t = i / 28
            a = 2 * math.pi * 2.4 * t + k * math.pi
            s = 0.05 + (L - 0.35) * t
            rr = np.interp(s, [p[0] for p in prof], [p[1] for p in prof]) + 0.012
            p = DRUM_REAR + ax * s + (side * math.cos(a) + upv * math.sin(a)) * rr
            if prev is not None:
                bar(m, "$drum", prev, p, 0.14, 0.05, "orange", up=(p - DRUM_REAR - ax * s))
            prev = p
    # pedestals, drive, water tank, hopper, ladder and platform
    cbox(m, "body", -0.95, 1.0, -2.85, 0.95, 2.45, -2.45, "paint2", c=0.06)
    cbox(m, "body", -0.8, 1.0, 1.45, 0.8, 1.95, 1.85, "paint2", c=0.06)
    cyl(m, "body", DRUM_FRONT - ax * 0.05, DRUM_FRONT + ax * 0.35, 0.32, n=12, tag="paint2")
    cyl(m, "body", (-0.6, 1.55, 2.0), (0.6, 1.55, 2.0), 0.3, n=12, tag="steel")
    m.add("body", mk.lathe(np.array([0.0, 3.03, -3.45]), (0, 1, 0), [(0.0, 0.28), (0.4, 0.55), (0.62, 0.68)],
                           n=12, tag="paint2"))
    for x in (-0.7, 0.7):
        bar(m, "body", (x, 1.05, -3.35), (x, 3.08, -3.35), 0.09, 0.09, "paint2", up=(0, 0, 1))
    for i in range(9):
        y = 1.2 + i * 0.27
        box(m, "body", 0.95, y, -3.4, 1.15, y + 0.04, -3.35, "steel")
    for x in (0.95, 1.15):
        box(m, "body", x - 0.02, 0.6, -3.42, x + 0.02, 3.4, -3.38, "steel")
    box(m, "body", 0.7, 3.2, -3.3, 1.2, 3.25, -2.5, "steel")
    tube(m, "body", [(1.2, 3.25, -3.3), (1.2, 3.65, -3.3), (1.2, 3.65, -2.5)], 0.025)
    # chute: swivel collector and trough, on a bracket from the rear frame
    bar(m, "body", (-0.72, 2.2, -3.35), (0.72, 2.2, -3.35), 0.1, 0.12, "paint2")
    bar(m, "body", (0.0, 2.2, -3.3), (0.0, 2.2, CHUTE[2]), 0.14, 0.12, "paint2")
    cyl(m, "$chute", CHUTE + [0, -0.12, 0], CHUTE + [0, 0.18, 0], 0.26, n=12, tag="paint2")
    u = unit(CHUTE_TIP - CHUTE)
    nrm = np.cross([1, 0, 0], u)
    nrm = nrm if nrm[1] > 0 else -nrm
    bar(m, "$chute_arm", CHUTE + u * 0.1, CHUTE_TIP, 0.5, 0.04, "orange", up=nrm)
    for sgn in (1, -1):
        bar(m, "$chute_arm", CHUTE + u * 0.1 + [sgn * 0.25, 0, 0] + nrm * 0.1,
            CHUTE_TIP + [sgn * 0.25, 0, 0] + nrm * 0.1, 0.03, 0.2, "orange", up=nrm)
    cyl(m, "$chute_arm", CHUTE + [-0.3, 0, 0], CHUTE + [0.3, 0, 0], 0.05, n=6, tag="steel")
    joints = [J("$drum", (DRUM_REAR + DRUM_FRONT) / 2, ax, mode="spin", channel="drum"),
              J("$chute", CHUTE, (0, 1, 0), channel="chute_swing"),
              J("$chute_arm", CHUTE, (1, 0, 0), channel="chute_tilt", parent="$chute")]
    spec = {
        "top": {**st, "wheel_parts": wheels, "width": 3.0, "height": 3.8},
        "construction": {"joints": joints,
                         "work_points": {"chute_tip": pt("$chute_arm", CHUTE_TIP + [0, -0.1, -0.1])}},
    }
    return m, spec, ()


# ====================================================================== tractor head + trailer
T_FRONT, T_REAR = 2.45, (-0.95, -2.25)
HITCH = np.array([0.0, 1.25, -1.45])
KINGPIN = np.array([0.0, 1.25, 5.4])


def tractor_head():
    m = model("tractor_head_detail")
    chassis(m, -3.0, 3.1)
    axles(m, (T_FRONT,) + T_REAR)
    wheels = truck_wheels(m, T_FRONT, T_REAR)
    st = cabover(m, "body", 1.45, 3.4, 3.05, T_FRONT, tag="red")
    m.add("body", mk.extrude_zy([(1.5, 3.03), (2.6, 3.03), (2.3, 3.45), (1.55, 3.55)], -1.15, 1.15, tag="red"))
    for s in (1, -1):
        m.add("body", mk.cylinder((s * 0.85, 0.85, 0.1), (s * 0.85, 0.85, 1.25), 0.3, n=14, tag="steel"))
        for z in (0.3, 1.0):
            box(m, "body", s * 0.5, 0.52, z - 0.05, s * 1.16, 0.58, z + 0.05, "black")
        cyl(m, "body", (s * 1.0, 1.1, 1.35), (s * 1.0, 3.6, 1.35), 0.075, n=10, tag="chrome")
        cyl(m, "body", (s * 0.6, 0.6, -0.1), (s * 0.6, 0.6, -0.6), 0.14, n=10, tag="steel")    # air tanks
    rear_end(m, -3.05, (-1.6,))
    box(m, "body", -0.75, 1.05, -2.05, 0.75, 1.15, -0.85, "paint2")                             # fifth wheel
    m.add("body", mk.extrude_xz([(0.8, -2.1), (0.8, -0.95), (0.3, -0.75), (-0.3, -0.75), (-0.8, -0.95),
                                 (-0.8, -2.1)], 1.15, 1.25, tag="black"))
    box(m, "body", -0.12, 1.15, -2.15, 0.12, 1.25, HITCH[2], "steel")
    tube(m, "body", [(0.3, 2.3, 1.42), (0.3, 1.7, 0.8), (0.3, 1.4, -0.3)], 0.025, tag="red", n=5)
    tube(m, "body", [(-0.3, 2.3, 1.42), (-0.3, 1.7, 0.8), (-0.3, 1.4, -0.3)], 0.025, tag="lamp", n=5)
    spec = {
        "top": {**st, "wheel_parts": wheels, "width": 3.0, "height": 3.1},
        "settings": {"tractor": {"hitch_x": 0.0, "hitch_y": HITCH[1], "hitch_z": HITCH[2]}},
    }
    return m, spec, ()


TR_DECK_Y = 0.85
TR_REAR_Y = 1.3
TR_HINGE = np.array([0.0, TR_REAR_Y, -6.45])
TR_AXLES = (-4.7, -5.7)
RAMP_LEN = 2.0


def lowboy_trailer():
    m = model("lowboy_trailer_detail")
    hw = 1.49
    # gooseneck over the tractor, kingpin underneath
    m.add("body", mk.extrude_zy([(3.5, 1.32), (6.2, 1.32), (6.25, 1.75), (3.4, 1.78)], -1.2, 1.2, tag="paint2"))
    cyl(m, "body", KINGPIN + [0, -0.05, 0], KINGPIN + [0, 0.1, 0], 0.07, n=8, tag="steel")
    box(m, "body", -0.6, 1.22, 4.9, 0.6, 1.33, 5.9, "steel")
    for s in (1, -1):
        bar(m, "body", (s * 1.05, 1.5, 3.55), (s * 1.3, 0.68, 2.85), 0.25, 0.32, "paint2", up=(1, 0, 0))
        bar(m, "body", (s * 0.4, 1.5, 3.55), (s * 0.4, 0.68, 2.85), 0.22, 0.32, "paint2", up=(1, 0, 0))
        box(m, "body", s * 1.05, 0.5, 3.75, s * 1.15, 1.35, 3.9, "paint2")                   # landing legs
        box(m, "body", s * 0.95, 0.45, 3.65, s * 1.25, 0.5, 4.0, "steel")
        lamp(m, "body", s * 1.0, 1.55, 6.25, 0.05, facing=(0, 0, 1), tag="orange")
    box(m, "body", -1.22, 1.68, 6.12, 1.22, 1.8, 6.28, "stripe")
    # low deck
    for s in (1, -1):
        box(m, "body", s * (hw - 0.2), 0.48, -4.05, s * hw, TR_DECK_Y, 2.95, "paint")
        for z in np.linspace(-3.8, 2.7, 10):
            box(m, "body", s * hw, 0.55, z - 0.05, s * (hw + 0.06), TR_DECK_Y, z + 0.05, "paint")
    for z in np.linspace(-3.8, 2.7, 13):
        box(m, "body", -hw + 0.18, 0.5, z - 0.06, hw - 0.18, 0.7, z + 0.06, "paint2")
    for i in range(11):
        x = -hw + 0.2 + i * 0.236
        box(m, "body", x, 0.7, -4.0, x + 0.226, TR_DECK_Y, 2.9, "wood")
    # raised rear deck over the bogie
    side_plate(m, "body", [(-4.1, 0.75), (-4.0, 0.75), (-4.0, TR_REAR_Y), (-4.1, TR_REAR_Y)], -hw, hw, "paint")
    for s in (1, -1):
        box(m, "body", s * (hw - 0.22), 0.8, -6.45, s * hw, TR_REAR_Y, -4.0, "paint")
    box(m, "body", -hw + 0.2, 1.05, -6.4, hw - 0.2, TR_REAR_Y - 0.05, -4.05, "paint2")
    for i in range(11):
        x = -hw + 0.2 + i * 0.236
        box(m, "body", x, TR_REAR_Y - 0.08, -6.4, x + 0.226, TR_REAR_Y, -4.05, "wood")
    box(m, "body", -hw, 0.85, -6.55, hw, TR_REAR_Y, -6.45, "stripe")
    for s in (1, -1):
        lamp(m, "body", s * 1.25, 1.05, -6.55, 0.07, facing=(0, 0, -1), tag="tail")
    for z in TR_AXLES:
        cyl(m, "body", (-0.85, 0.4, z), (0.85, 0.4, z), 0.08, n=8, tag="paint2")
        for s in (1, -1):
            box(m, "body", s * 0.45, 0.48, z - 0.5, s * 0.6, 1.06, z + 0.5, "steel")
    wheels = []
    i = 0
    for z in TR_AXLES:
        for x in (1.1, -1.1):
            wheel(m, "$wheel%d" % i, x, 0.4, z, 0.4, 0.55, dual=True)
            wheels.append(("$wheel%d" % i, (x, 0.4, z), 0)); i += 1
    # loading ramps standing up at the rear; they swing back and down onto the ground
    for side, x0, x1 in (("l", 0.35, 1.35), ("r", -1.35, -0.35)):
        g = "$hatch_ramp_" + side
        z0 = TR_HINGE[2] - 0.12
        box(m, g, x0, TR_REAR_Y, z0 - 0.1, x1, TR_REAR_Y + RAMP_LEN, z0, "paint")
        for x in (x0, x1 - 0.07):
            box(m, g, x, TR_REAR_Y, z0 - 0.14, x + 0.07, TR_REAR_Y + RAMP_LEN, z0 + 0.02, "paint2")
        for k in range(10):
            y = TR_REAR_Y + 0.15 + k * 0.18
            box(m, g, x0 + 0.07, y, z0 - 0.16, x1 - 0.07, y + 0.04, z0 - 0.1, "steel")
        cyl(m, g, (x0, TR_HINGE[1], TR_HINGE[2]), (x1, TR_HINGE[1], TR_HINGE[2]), 0.06, n=6, tag="steel")
    angle = round(math.degrees(math.acos(-TR_REAR_Y / RAMP_LEN)), 1)   # ramp tips just touch the ground
    toggle = [{"part": "$hatch_ramp_" + s, "pivot_x": 0.0, "pivot_y": float(TR_HINGE[1]),
               "pivot_z": float(TR_HINGE[2]), "mode": "rotate", "axis_x": -1.0, "axis_y": 0.0, "axis_z": 0.0,
               "max_angle": angle, "trigger": "key", "speed": 2.0} for s in ("l", "r")]
    ramp_z = float(TR_HINGE[2]) - RAMP_LEN * math.sin(math.radians(angle))
    runways = [
        {"width": 2.9, "center_x": 0.0, "height_y": TR_DECK_Y, "start_z": -4.0, "end_z": 2.9},
        {"width": 2.9, "center_x": 0.0, "height_y": TR_REAR_Y, "start_z": float(TR_HINGE[2]), "end_z": -4.0},
        {"width": 2.9, "center_x": 0.0, "height_y": round(TR_REAR_Y / 2, 3), "start_z": round(ramp_z, 3),
         "end_z": float(TR_HINGE[2]), "hatch_gated": True},
    ]
    spec = {
        "top": {"wheel_parts": wheel_parts(wheels), "toggle_parts": toggle, "runways": runways, "width": 3.0,
                "height": 1.8},
        "settings": {"trailer": {"kingpin_x": 0.0, "kingpin_y": float(KINGPIN[1]), "kingpin_z": float(KINGPIN[2]),
                                 "axle_z": sum(TR_AXLES) / 2}},
    }
    return m, spec, ()
