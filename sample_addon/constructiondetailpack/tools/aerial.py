"""Aerial work platforms (original designs): an 8 m class compact electric scissor lift for indoor / plant
work, a 12 m class insulated bucket truck for overhead line work, and a 12 m class trailer-mounted
articulated boom lift for building sites.

Class reference (general published ranges, compared for structure and size only):
  Compact scissor lift: platform height ~6-8 m, width ~0.8 m (fits a door), length ~2.3-2.5 m, stowed
    platform ~1.0-1.2 m, roll-out deck ~0.9 m, solid non-marking tyres, pothole protection.
  Insulated bucket truck (4 t chassis): working height ~12-15 m, 3-4 section telescopic boom on a turret,
    1-2 person insulated bucket, H-type outriggers, boom stowed over the body.
  Trailer boom lift: working height ~12-14 m, single axle, articulated lower boom + telescopic upper boom,
    four swing-down outriggers, 1-2 person basket.
"""
import math

import numpy as np

from common import (box, cbox, cyl, bar, side_plate, plan_plate, tube, lamp, wheel, actuator, model, mk, J, pt,
                    wheel_parts, open_scale, open_seat, guard_rails, riders_json, rot)
from trucks import cabover, chassis, axles, truck_wheels, rear_end, HW

SWING_ALARM = {"sound": "minecraft:block.note_block.bit", "volume": 0.6, "pitch": 1.8, "interval": 8}


# ====================================================================== compact scissor lift
SL_LEVELS = 5
SL_ARM = 1.9           # pin to pin
SL_Y0 = 0.47           # lowest pins (on the chassis)
SL_H0 = 0.096          # rise of one level, stowed
SL_HM = 1.12           # rise of one level, fully raised (platform floor ~6.2 m)
SL_ZF = -0.98          # fixed (rear) pins
SL_EXT = 0.9           # roll-out deck travel


def scissor_lift():
    m = model("scissor_lift_8m_detail")
    # ---------------------------------------------------------------- chassis
    cbox(m, "body", -0.405, 0.16, -1.22, 0.405, 0.44, 1.22, "paint", c=0.05)
    for s in (1, -1):
        # component trays (batteries one side, pump / tank the other) with hinged lids
        cbox(m, "body", s * 0.27, 0.17, -0.95, s * 0.41, 0.42, 0.55, "paint2", c=0.02)
        for z in (-0.6, -0.1, 0.35):
            box(m, "body", s * 0.408, 0.38, z - 0.04, s * 0.415, 0.4, z + 0.04, "black")       # latch
        box(m, "body", s * 0.39, 0.06, -0.75, s * 0.41, 0.14, 0.45, "steel")                    # pothole guard
    box(m, "body", -0.42, 0.22, 1.18, 0.42, 0.36, 1.26, "black")                               # bumpers
    box(m, "body", -0.42, 0.22, -1.26, 0.42, 0.36, -1.18, "black")
    lamp(m, "body", 0.3, 0.46, -1.1, 0.05, facing=(0, 1, 0), tag="orange")                    # beacon
    cbox(m, "body", -0.12, 0.44, -1.2, 0.12, 0.52, -1.0, "paint2", c=0.02)                     # ground controls
    wheels = []
    for i, (x, z, steer) in enumerate([(0.32, 0.86, 45.0), (-0.32, 0.86, 45.0), (0.32, -0.86, 0), (-0.32, -0.86, 0)]):
        wheel(m, "$wheel%d" % i, x, 0.19, z, 0.19, 0.13)
        wheels.append(("$wheel%d" % i, (x, 0.19, z), steer))
    # ---------------------------------------------------------------- scissor stack
    lift = SL_HM - SL_H0
    c0 = math.sqrt(SL_ARM ** 2 - SL_H0 ** 2)
    joints = []
    for k in range(SL_LEVELS):
        y_k, y_n = SL_Y0 + k * SL_H0, SL_Y0 + (k + 1) * SL_H0
        frame = "$lv%d" % k if k else None
        top = "$lv%d" % (k + 1)
        a, b = "$sa%d" % k, "$sb%d" % k
        for s in (1, -1):
            bar(m, a, (s * 0.36, y_k, SL_ZF), (s * 0.36, y_n, SL_ZF + c0), 0.05, 0.11, "paint2")
            bar(m, b, (s * 0.29, y_k, SL_ZF + c0), (s * 0.29, y_n, SL_ZF), 0.05, 0.11, "paint2")
        cyl(m, a, (-0.4, y_n, SL_ZF + c0), (0.4, y_n, SL_ZF + c0), 0.035, n=8, tag="steel")         # rolling pin
        cyl(m, b, (-0.33, y_k, SL_ZF + c0), (0.33, y_k, SL_ZF + c0), 0.035, n=8, tag="steel")
        cyl(m, a, (-0.4, (y_k + y_n) / 2, SL_ZF + c0 / 2), (0.4, (y_k + y_n) / 2, SL_ZF + c0 / 2), 0.04, n=8,
            tag="steel")                                                                                  # centre pin
        for s in (1, -1):                                                                                 # rollers
            cyl(m, a, (s * 0.41, y_n, SL_ZF + c0), (s * 0.44, y_n, SL_ZF + c0), 0.05, n=8, tag="black")
        if k:
            cyl(m, frame, (-0.42, y_k, SL_ZF), (0.42, y_k, SL_ZF), 0.04, n=8, tag="steel")              # fixed pin
            for s in (1, -1):
                box(m, frame, s * 0.39, y_k - 0.05, SL_ZF - 0.06, s * 0.43, y_k + 0.05, SL_ZF + 0.06, "paint2")
        joints.append(J(a, (0, y_k, SL_ZF), (-1, 0, 0), mode="scissor", channel="lift", parent=frame,
                        factor=round(lift / SL_ARM, 5), offset=round(SL_H0 / SL_ARM, 5)))
        joints.append(J(b, (0, y_n, SL_ZF), (1, 0, 0), mode="scissor", channel="lift", parent=top,
                        factor=round(lift / SL_ARM, 5), offset=round(SL_H0 / SL_ARM, 5)))
    for k in range(1, SL_LEVELS + 1):
        joints.append(J("$lv%d" % k, (0, 0, 0), (0, 1, 0), mode="slide", channel="lift", factor=round(k * lift, 4)))
    cyl(m, "body", (-0.42, SL_Y0, SL_ZF), (0.42, SL_Y0, SL_ZF), 0.04, n=8, tag="steel")
    for s in (1, -1):
        box(m, "body", s * 0.39, 0.42, SL_ZF - 0.06, s * 0.43, SL_Y0 + 0.05, SL_ZF + 0.06, "paint2")
        box(m, "body", s * 0.38, 0.42, -0.2, s * 0.45, 0.45, 1.05, "steel")                           # roller track
    # ---------------------------------------------------------------- platform and roll-out deck
    P = "$lv%d" % SL_LEVELS
    fy = SL_Y0 + SL_LEVELS * SL_H0
    floor = fy + 0.1
    cbox(m, P, -0.405, fy, -1.17, 0.405, floor, 1.12, "paint2", c=0.02)
    for s in (1, -1):
        box(m, P, s * 0.35, fy - 0.05, SL_ZF - 0.07, s * 0.43, fy + 0.02, SL_ZF + c0 + 0.07, "paint2")   # roller rails
        box(m, P, s * 0.39, fy - 0.07, SL_ZF - 0.05, s * 0.43, fy, SL_ZF + 0.05, "paint2")
    guard_rails(m, P, -0.405, 0.405, floor, -1.17, 0.35, h=1.1, tag="paint", sides=("l", "r", "b"), gate=(-0.2, 0.2))
    cbox(m, P, 0.29, floor + 0.78, 0.0, 0.4, floor + 0.92, 0.16, "paint2", c=0.015)                # control box
    E = "$deck_ext"
    cbox(m, E, -0.38, floor, 0.3, 0.38, floor + 0.05, 1.12, "steel", c=0.01)
    for s in (1, -1):                                                     # runners in guides under the main deck
        box(m, E, s * 0.2 - 0.04, fy - 0.06, -0.75, s * 0.2 + 0.04, fy, 1.12, "steel")
        box(m, P, s * 0.2 - 0.06, fy - 0.08, -0.8, s * 0.2 + 0.06, fy - 0.06, 1.12, "paint2")
    box(m, E, -0.38, fy - 0.06, 1.12, 0.38, floor + 0.05, 1.16, "steel")                           # front lip
    guard_rails(m, E, -0.385, 0.385, floor + 0.05, 0.3, 1.12, h=1.05, tag="paint", sides=("l", "r", "f"))
    joints.append(J(E, (0, 0, 0), (0, 0, 1), mode="slide", channel="extend", parent=P))
    s = open_scale(-0.38, 0.38, -1.14, 0.35)
    st = open_seat(m, P, floor, -0.38, 0.38, -1.14, 0.35, 0.0, s, "operator", True, eye_over=1.35)
    spec = {
        "top": {**riders_json([st], s), "wheel_parts": wheel_parts(wheels), "width": 1.0, "height": 2.0},
        "construction": {"joints": joints, "work_points": {"basket": pt(P, (0, floor, -0.4))},
                         "seat_parts": [{"seat": 0, "part": P}], "work_seat": 0},
        "settings": {"aerial_platform": {"lift_speed": 0.005, "extend_max": SL_EXT, "extend_speed": 0.02}},
    }
    return m, spec, ()


# ====================================================================== insulated bucket truck
BT_FRONT, BT_REAR = 2.55, -1.15
BT_TURRET = np.array([0.0, 1.45, -2.5])
BT_LUFF = np.array([0.0, 2.25, -2.62])
BT_TIP = np.array([0.0, 2.2, 0.42])           # bucket pin, stowed (boom forward over the body)
BT_EXT = 7.5                                  # telescoping (3 sections)
BT_OUT = (1.25, -3.25)                        # outrigger beams


def bucket_truck():
    m = model("bucket_truck_12m_detail")
    chassis(m, -3.55, 3.3)
    axles(m, (BT_FRONT, BT_REAR))
    wheels = truck_wheels(m, BT_FRONT, (BT_REAR,))
    s_b = open_scale(-0.38, 0.38, 0.55, 1.47)
    st = cabover(m, "body", 1.55, 3.35, 2.95, BT_FRONT, tag="white", max_scale=s_b)
    s = st["passenger_display"]["entity_width"]
    rear_end(m, -3.6, (BT_REAR,))
    cbox(m, "body", -1.05, 0.5, -0.4, -0.55, 0.95, 0.6, "steel", c=0.06)                       # fuel tank
    cbox(m, "body", 0.55, 0.45, -0.6, 1.1, 1.0, 0.7, "paint2", c=0.04)                         # tool box
    # sub-frame and body (tool lockers each side, a deck in between)
    box(m, "body", -1.12, 1.05, -3.55, 1.12, 1.18, 1.45, "steel")
    for sx in (1, -1):
        cbox(m, "body", sx * 0.62, 1.18, -1.6, sx * 1.12, 1.95, 1.0, "white", c=0.03)
        for z in (-1.05, -0.2, 0.6):
            box(m, "body", sx * 1.12, 1.25, z - 0.38, sx * 1.125, 1.88, z + 0.38, "paint2")         # locker doors
            box(m, "body", sx * 1.12, 1.55, z + 0.25, sx * 1.135, 1.6, z + 0.35, "chrome")
    # outriggers: H type - a beam out sideways from a box across the chassis, then the jack down
    joints = []
    for z in BT_OUT:
        box(m, "body", -1.0, 0.7, z - 0.18, 1.0, 1.02, z + 0.18, "paint2")
    for side, sx in (("l", 1), ("r", -1)):
        g, leg = "$outrigger_" + side, "$outrigger_%s_leg" % side
        for z in BT_OUT:
            dz = sx * 0.07
            box(m, g, sx * -0.85, 0.74, z + dz - 0.08, sx * 1.12, 0.96, z + dz + 0.08, "white")
            cyl(m, g, (sx * 1.15, 0.6, z + dz), (sx * 1.15, 1.32, z + dz), 0.09, n=8, tag="white")  # jack cylinder
            cyl(m, leg, (sx * 1.15, 0.68, z + dz), (sx * 1.15, 1.28, z + dz), 0.055, n=6, tag="chrome")
            cyl(m, leg, (sx * 1.15, 0.6, z + dz), (sx * 1.15, 0.68, z + dz), 0.2, n=10, tag="paint2")  # pad (raised)
            box(m, g, sx * 1.07, 0.85, z + dz - 0.1, sx * 1.24, 0.95, z + dz + 0.1, "stripe")
        joints.append(J(g, (0, 0, 0), (sx, 0, 0), mode="slide", channel="outrigger", factor=1.6, min=0.0, max=0.8))
        joints.append(J(leg, (0, 0, 0), (0, -1, 0), mode="slide", channel="outrigger", parent=g, factor=1.2,
                        offset=-0.6, min=0.0, max=0.6))
    # turret at the rear of the body
    cyl(m, "body", (0, 1.18, BT_TURRET[2]), (0, 1.3, BT_TURRET[2]), 0.62, n=20, tag="paint2")
    U = "$upper"
    cyl(m, U, (0, 1.3, BT_TURRET[2]), (0, 1.45, BT_TURRET[2]), 0.58, n=20, tag="white")
    side_plate(m, U, [(-3.05, 1.45), (-2.1, 1.45), (-2.2, 2.3), (-2.75, 2.55), (-3.05, 2.4)], -0.32, 0.32, "white")
    cbox(m, U, -0.55, 1.45, -3.25, 0.55, 1.95, -2.9, "paint2", c=0.04)                         # counterweight / valves
    box(m, "body", -0.2, 1.18, -0.05, 0.2, 1.95, 0.1, "paint2")                                 # boom rest
    box(m, "body", -0.3, 1.95, -0.1, 0.3, 2.02, 0.15, "black")
    # boom: base section + 3 telescopic, the last one insulated (orange band = insulation)
    B1, B2, B3, B4 = "$boom1", "$boom2", "$boom3", "$boom4"
    box(m, B1, -0.24, 2.02, -2.95, 0.24, 2.5, 0.35, "white")
    box(m, B2, -0.2, 2.06, -2.8, 0.2, 2.46, 0.4, "white")
    box(m, B3, -0.16, 2.1, -2.7, 0.16, 2.42, 0.45, "white")
    box(m, B4, -0.12, 2.14, -2.6, 0.12, 2.38, 0.5, "white")
    box(m, B4, -0.125, 2.135, -0.4, 0.125, 2.385, -0.1, "orange")
    cbox(m, B4, -0.16, 2.08, 0.38, 0.16, 2.42, 0.58, "paint2", c=0.03)                        # tip head
    for i, g in enumerate((B1, B2, B3)):
        z = (0.35, 0.4, 0.45)[i]
        w = (0.24, 0.2, 0.16)[i]
        box(m, g, -w - 0.01, 2.02 + 0.04 * i - 0.01, z - 0.08, w + 0.01, 2.5 - 0.04 * i + 0.01, z, "paint2")  # collars
    tube(m, B1, [(0.26, 2.3, -2.5), (0.26, 2.3, 0.2)], 0.025, tag="black")                      # hoses
    # bucket: hangs from the tip pin, kept level, turns on its own pin
    K = "$bucket"
    cbox(m, K, -0.07, 1.25, 0.42, 0.07, 2.25, 0.56, "paint2", c=0.02)                          # hanger post
    floor = 1.27
    box(m, K, -0.42, floor - 0.06, 0.52, 0.42, floor, 1.52, "white")
    for x0, x1, z0, z1 in ((-0.42, 0.42, 0.52, 0.57), (-0.42, 0.42, 1.47, 1.52), (-0.42, -0.37, 0.52, 1.52),
                           (0.37, 0.42, 0.52, 1.52)):
        box(m, K, x0, floor - 0.06, z0, x1, floor + 1.05, z1, "white")
    for x0, x1, z0, z1 in ((-0.44, 0.44, 0.5, 0.58), (-0.44, 0.44, 1.46, 1.54), (-0.44, -0.36, 0.58, 1.46),
                           (0.36, 0.44, 0.58, 1.46)):
        box(m, K, x0, floor + 1.0, z0, x1, floor + 1.07, z1, "paint2")                           # rim
    box(m, K, -0.44, floor + 0.4, 0.5, 0.44, floor + 0.45, 1.54, "orange")                      # band
    cbox(m, K, -0.25, floor + 0.85, 0.58, 0.25, floor + 1.05, 0.66, "paint2", c=0.02)           # controls on the rim
    st_b = open_seat(m, K, floor, -0.37, 0.37, 0.57, 1.47, 0.0, s, "bucket", False, eye_over=1.3)
    j_ram, err = actuator(m, "luff_ram", U, np.array([0.0, 1.6, -2.15]), np.array([0.0, 2.02, -1.05]), B1,
                          BT_LUFF, (-1, 0, 0), 0, 80, 0.13, tag="white", barrel_frac=0.6)
    box(m, B1, -0.1, 1.95, -1.15, 0.1, 2.03, -0.95, "paint2")
    joints += [
        J(U, BT_TURRET, (0, 1, 0), channel="swing"),
        J(B1, BT_LUFF, (-1, 0, 0), channel="luff", parent=U),
        J(B2, (0, 0, 0), (0, 0, 1), mode="slide", channel="extend", factor=round(1 / 3, 4), parent=B1),
        J(B3, (0, 0, 0), (0, 0, 1), mode="slide", channel="extend", factor=round(1 / 3, 4), parent=B2),
        J(B4, (0, 0, 0), (0, 0, 1), mode="slide", channel="extend", factor=round(1 / 3, 4), parent=B3),
        J("$bucket_level", BT_TIP, (-1, 0, 0), channel="level", parent=B4),
        J(K, BT_TIP, (0, 1, 0), channel="basket", parent="$bucket_level"),
    ] + j_ram
    spec = {
        "top": {**riders_json(st["seats"] + [st_b], s), "wheel_parts": wheels, "width": 2.5, "height": 3.0},
        "construction": {"joints": joints, "work_points": {"basket": pt(K, (0, floor, 1.0))},
                         "seat_parts": [{"seat": 1, "part": K}], "work_seat": 1, "swing_sound": dict(SWING_ALARM)},
        "settings": {"aerial_platform": {"extend_max": BT_EXT, "extend_speed": 0.05, "luff_max": 80.0,
                                         # stowed forward over the body: no telescoping into the cab
                                         "swing_clear_luff": 0.0, "extend_clear_luff": 30.0,
                                         "extend_front_sector": 35.0}},
    }
    return m, spec, (err,)


# ====================================================================== trailer boom lift
TL_KINGPIN = np.array([0.0, 1.1, 3.15])
TL_TURRET = np.array([0.0, 0.62, -0.2])
TL_LUFF = np.array([0.0, 1.05, 0.0])          # lower boom foot (stowed pointing forward)
TL_KNUCKLE = np.array([0.0, 1.3, 2.35])       # upper boom pin (upper stowed pointing back)
TL_TIP = np.array([0.0, 1.3, -1.05])          # basket pin, stowed
TL_LEGS = ((1.2, 1), (-1.85, -1))             # outrigger hinge z, fore / aft


def trailer_boom_lift():
    m = model("trailer_boom_lift_12m_detail")
    # ---------------------------------------------------------------- chassis
    for sx in (1, -1):
        box(m, "body", sx * 0.42, 0.38, -2.4, sx * 0.55, 0.62, 1.6, "paint2")                 # side members
    for z in (-2.3, -1.2, 0.0, 1.1):
        box(m, "body", -0.42, 0.42, z - 0.06, 0.42, 0.58, z + 0.06, "paint2")
    bar(m, "body", (0.35, 0.5, 1.5), (0.05, 1.0, 3.0), 0.12, 0.14, "paint2")                     # A-frame tongue
    bar(m, "body", (-0.35, 0.5, 1.5), (-0.05, 1.0, 3.0), 0.12, 0.14, "paint2")
    cbox(m, "body", -0.28, 0.95, 2.95, 0.28, 1.08, 3.35, "steel", c=0.02)                     # kingpin plate
    cyl(m, "body", (0, 0.8, 2.6), (0, 1.0, 2.6), 0.05, n=6, tag="steel")
    cyl(m, "body", (0, 0.1, 2.6), (0, 0.8, 2.6), 0.035, n=6, tag="chrome")                     # jockey stand
    cyl(m, "body", (0, 0.05, 2.6), (0, 0.1, 2.6), 0.12, n=8, tag="black")
    wheels = []
    for i, sx in enumerate((1, -1)):
        wheel(m, "$wheel%d" % i, sx * 0.86, 0.36, -0.95, 0.36, 0.26)
        wheels.append(("$wheel%d" % i, (sx * 0.86, 0.36, -0.95), 0))
        side_plate(m, "body", [(-1.45, 0.76), (-1.25, 0.82), (-0.65, 0.82), (-0.45, 0.76)], sx * 0.72, sx * 1.0,
                   "paint")                                                                       # mudguard
        box(m, "body", sx * 0.55, 0.32, -1.0, sx * 0.72, 0.42, -0.9, "steel")                     # axle stub
        lamp(m, "body", sx * 0.4, 0.55, -2.42, 0.05, facing=(0, 0, -1), tag="tail")
    cyl(m, "body", (-0.55, 0.36, -0.95), (0.55, 0.36, -0.95), 0.06, n=8, tag="paint2")
    # ---------------------------------------------------------------- swing-down outriggers
    # Modelled down (deployed); "outrigger" 0 turns them 90 deg up beside the frame, then their feet lift.
    joints = []
    for z, f in TL_LEGS:
        for side, sx in (("l", 1), ("r", -1)):
            name = "$leg_%s%s" % ("f" if f > 0 else "r", side)
            foot = name + "_foot"
            hinge = np.array([sx * 0.58, 0.55, z])
            tip = hinge + np.array([sx * 1.05, 0.0, f * 0.35])
            box(m, "body", sx * 0.52, 0.45, z - 0.1, sx * 0.64, 0.65, z + 0.1, "paint2")          # hinge bracket
            bar(m, name, hinge, tip, 0.1, 0.12, "paint")
            cyl(m, name, tip + [0, -0.4, 0], tip + [0, 0.08, 0], 0.07, n=8, tag="paint")          # jack cylinder (under)
            cyl(m, foot, tip + [0, -0.5, 0], tip + [0, -0.1, 0], 0.035, n=6, tag="chrome")
            cyl(m, foot, tip + [0, -0.55, 0], tip + [0, -0.5, 0], 0.15, n=10, tag="paint2")
            joints.append(J(name, hinge, (0, 0, -sx), channel="outrigger", factor=180.0, offset=-90.0,
                            min=-90.0, max=0.0))
            joints.append(J(foot, (0, 0, 0), (0, 1, 0), mode="slide", channel="outrigger", parent=name,
                            factor=-0.3, offset=0.3, min=0.0, max=0.15))
    # ---------------------------------------------------------------- turret and booms
    cyl(m, "body", (0, 0.58, TL_TURRET[2]), (0, 0.66, TL_TURRET[2]), 0.42, n=16, tag="paint2")
    U = "$upper"
    cbox(m, U, -0.4, 0.66, -0.6, 0.4, 0.95, 0.2, "paint", c=0.04)
    side_plate(m, U, [(-0.3, 0.95), (0.3, 0.95), (0.2, 1.2), (-0.2, 1.25)], -0.18, 0.18, "paint")       # boom foot
    cbox(m, U, -0.38, 0.62, 0.2, 0.38, 0.88, 0.62, "paint2", c=0.04)                           # power pack
    B1, B2, B3 = "$boom1", "$boom2", "$boom3"
    box(m, B1, -0.12, 0.93, -0.15, 0.12, 1.17, 2.45, "white")
    box(m, B2, -0.1, 1.19, -0.85, 0.1, 1.41, 2.45, "white")
    cbox(m, B2, -0.13, 1.17, 2.25, 0.13, 1.43, 2.5, "paint2", c=0.02)                          # knuckle head
    box(m, B3, -0.075, 1.22, -1.0, 0.075, 1.38, 1.6, "paint")
    cbox(m, B3, -0.09, 1.2, -1.12, 0.09, 1.4, -0.98, "paint2", c=0.02)
    cbox(m, "body", -0.15, 0.62, 1.85, 0.15, 0.93, 2.15, "paint2", c=0.03)                     # boom rest
    # basket (aluminium, open rails), kept level by two joints undoing the two booms' angles
    K = "$basket"
    floor = 0.86
    cbox(m, K, -0.06, floor, -1.15, 0.06, 1.32, -1.03, "paint2", c=0.02)                       # hanger
    box(m, K, -0.4, floor - 0.05, -2.35, 0.4, floor, -1.12, "steel")
    guard_rails(m, K, -0.4, 0.4, floor, -2.35, -1.12, h=1.1, tag="steel", gate=(-0.18, 0.18))
    cbox(m, K, -0.22, floor + 0.85, -1.2, 0.22, floor + 1.05, -1.12, "paint2", c=0.02)         # controls
    s = open_scale(-0.37, 0.37, -2.32, -1.15)
    st = open_seat(m, K, floor, -0.37, 0.37, -2.32, -1.15, 0.0, s, "basket", False, eye_over=1.35)
    j1, e1 = actuator(m, "luff_ram", U, np.array([0.0, 0.8, -0.55]), np.array([0.0, 0.93, 0.9]), B1, TL_LUFF,
                      (-1, 0, 0), 0, 80, 0.09, tag="paint")
    j2, e2 = actuator(m, "knuckle_ram", B1, np.array([0.0, 1.05, 1.2]), np.array([0.0, 1.32, 1.75]), B2, TL_KNUCKLE,
                      (1, 0, 0), 0, 165, 0.08, tag="paint")
    joints += [
        J(U, TL_TURRET, (0, 1, 0), channel="swing"),
        J(B1, TL_LUFF, (-1, 0, 0), channel="luff", parent=U),
        J(B2, TL_KNUCKLE, (1, 0, 0), channel="knuckle", parent=B1),
        J(B3, (0, 0, 0), (0, 0, -1), mode="slide", channel="extend", parent=B2),
        J("$basket_l1", TL_TIP, (1, 0, 0), channel="luff", parent=B3),
        J("$basket_l2", TL_TIP, (1, 0, 0), channel="knuckle", factor=-1.0, parent="$basket_l1"),
        J(K, TL_TIP, (0, 1, 0), channel="basket", parent="$basket_l2"),
    ] + j1 + j2
    spec = {
        "top": {**riders_json([st], s), "wheel_parts": wheel_parts(wheels), "width": 1.8, "height": 2.0},
        "construction": {"joints": joints, "work_points": {"basket": pt(K, (0, floor, -1.7))},
                         "seat_parts": [{"seat": 0, "part": K}], "work_seat": 0, "swing_sound": dict(SWING_ALARM)},
        "settings": {"aerial_platform": {
            "luff_max": 80.0, "knuckle_max": 165.0, "extend_max": 1.8, "extend_speed": 0.03,
            "swing_clear_luff": 20.0, "swing_low_limit": 60.0,
            "tow": {"kingpin_x": 0.0, "kingpin_y": round(float(TL_KINGPIN[1]), 4),
                    "kingpin_z": round(float(TL_KINGPIN[2]), 4), "axle_z": -0.95, "max_articulation": 80.0}}},
    }
    return m, spec, (e1, e2)
