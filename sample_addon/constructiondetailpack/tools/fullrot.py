"""Full-rotation (全周回転) casing / steel pipe pile machines (original designs): a self-propelled crawler type
carrying the unit at its front, and a stationary unit set on the ground with its power pack.

Class reference (general published ranges, compared for structure and size only): casing / pile diameters
~1.0-2.0 m, the unit a round housing ~3 m across on four jack legs, a thrust table moved by four thrust
cylinders over a stroke of ~0.5-0.7 m, a rotating ring with chuck jaws turned by hydraulic motors.

Joints: the jaws' bottom = "head" (the thrust table slides with it), "chuck" opens the jaws, "rpm" turns
the ring and the gripped pile, "pile_top" / "pile_length" carry the pile, "tool" shows the follower
(ヤットコ) standing on the pile's head with its cap at its foot.
"""
import math

import numpy as np

from common import (box, cbox, cyl, bar, side_plate, plan_plate, tube, lamp, cab, cab_rider, crawler,
                    merge_crawlers, model, J, pt, open_scale, open_seat, guard_rails, riders_json, annulus)

PILE_R = 0.6          # casing radius (1.2 m)
GRIP = 1.3            # jaws' bottom as modelled (head_min)
STROKE = 0.7
TOP = 2.6             # pile top as modelled
TOOL = 3.5            # follower length
CHUCK_LEN = 0.6


def unit(m, cz, base_y, leg_lift=0.0):
    """The full-rotation unit centred on (0, cz). Returns the joints."""
    h0, h1 = base_y, base_y + 0.8
    joints = []
    # round housing with lugs for the four jack legs (on the diagonals)
    annulus(m, "body", 0.0, cz, 0.78, 1.45, h0, h1, n=20, tag="paint")
    annulus(m, "body", 0.0, cz, 1.4, 1.48, h1 - 0.1, h1, n=20, tag="paint2")
    for i in range(4):
        a = math.pi / 4 + i * math.pi / 2
        c, s = math.cos(a), math.sin(a)
        x, z = c * 1.62, cz + s * 1.62
        bar(m, "body", (c * 1.3, h1 - 0.25, cz + s * 1.3), (x, h1 - 0.25, z), 0.3, 0.3, "paint2")
        cyl(m, "body", (x, h0 - 0.05, z), (x, h1 + 0.25, z), 0.16, n=10, tag="paint")             # jack cylinder
        leg = "$unit_leg%d" % i
        cyl(m, leg, (x, 0.08, z), (x, h0 + 0.3, z), 0.09, n=8, tag="chrome")
        cyl(m, leg, (x, 0.0, z), (x, 0.08, z), 0.32, n=12, tag="paint2")                          # pad
        if leg_lift > 0:
            joints.append(J(leg, (0, 0, 0), (0, 1, 0), mode="slide", channel="outrigger",
                            factor=round(-leg_lift, 4), offset=round(leg_lift, 4)))
        # thrust cylinder (barrel inside the housing, a collar on top, the rod up to the table)
        tx, tz = math.cos(i * math.pi / 2) * 1.12, cz + math.sin(i * math.pi / 2) * 1.12
        cyl(m, "body", (tx, h1, tz), (tx, h1 + 0.12, tz), 0.12, n=8, tag="steel")
        cyl(m, "$table", (tx, h0 + 0.05, tz), (tx, GRIP - 0.05, tz), 0.07, n=8, tag="chrome")
    # thrust table, drive motors
    t0, t1 = GRIP - 0.05, GRIP + CHUCK_LEN + 0.05
    annulus(m, "$table", 0.0, cz, 0.78, 1.38, t0, t0 + 0.16, n=20, tag="paint2")
    for x in (1.0, -1.0):
        cbox(m, "$table", x - 0.2, t0 + 0.16, cz - 0.25, x + 0.2, t0 + 0.6, cz + 0.25, "paint", c=0.03)
        cyl(m, "$table", (x, t0 + 0.6, cz), (x, t0 + 0.75, cz), 0.12, n=8, tag="steel")
        tube(m, "$table", [(x * 1.2, t0 + 0.4, cz + 0.2), (x * 1.55, t0 + 0.3, cz + 0.6), (x * 1.6, h1, cz + 0.9)],
             0.03, tag="black")
    # rotating ring with its gear teeth, and six chuck jaws
    annulus(m, "$ring", 0.0, cz, 0.72, 0.95, t0 + 0.16, t1, n=24, tag="paint")
    for i in range(24):
        a = 2 * math.pi * (i + 0.5) / 24
        cbox(m, "$ring", math.cos(a) * 0.98 - 0.03, t0 + 0.2, cz + math.sin(a) * 0.98 - 0.03,
             math.cos(a) * 0.98 + 0.03, t0 + 0.4, cz + math.sin(a) * 0.98 + 0.03, "steel", c=0.005)
    for i in range(6):
        a = 2 * math.pi * i / 6
        c, s = math.cos(a), math.sin(a)
        jaw = "$jaw%d" % i
        plan_plate(m, jaw, [(c * (PILE_R + 0.005) - s * 0.15, cz + s * (PILE_R + 0.005) + c * 0.15),
                            (c * 0.76 - s * 0.2, cz + s * 0.76 + c * 0.2),
                            (c * 0.76 + s * 0.2, cz + s * 0.76 - c * 0.2),
                            (c * (PILE_R + 0.005) + s * 0.15, cz + s * (PILE_R + 0.005) - c * 0.15)],
                   GRIP, GRIP + CHUCK_LEN, "steel")
        joints.append(J(jaw, (0, 0, 0), (round(c, 4), 0, round(s, 4)), mode="slide", channel="chuck",
                        factor=0.1, parent="$ring"))
    joints += [
        J("$table", (0, 0, 0), (0, 1, 0), mode="slide", channel="head", offset=round(-GRIP, 4)),
        J("$ring", (0, 0, cz), (0, 1, 0), mode="spin", channel="rpm", factor=0.3, parent="$table"),
    ]
    # casing (pile) with cutting teeth at its toe, and the follower standing on its head
    tip_h = 0.45
    shaft = TOP - tip_h
    cyl(m, "$pile_shaft", (0, tip_h, cz), (0, TOP, cz), PILE_R, n=20, tag="steel")
    cyl(m, "$pile_shaft", (0, TOP - 0.15, cz), (0, TOP, cz), PILE_R * 1.03, n=20, tag="paint2")   # joint band
    cyl(m, "$pile_tip", (0, 0.06, cz), (0, tip_h + 0.02, cz), PILE_R * 1.02, n=20, tag="paint2")
    for i in range(12):
        a = 2 * math.pi * i / 12
        cbox(m, "$pile_tip", math.cos(a) * PILE_R - 0.05, 0.0, cz + math.sin(a) * PILE_R - 0.05,
             math.cos(a) * PILE_R + 0.05, 0.1, cz + math.sin(a) * PILE_R + 0.05, "black", c=0.01)   # bits
    hide = TOP - 0.05
    cyl(m, "$follower", (0, TOP, cz), (0, TOP + 0.3, cz), PILE_R * 1.06, n=20, tag="paint2")        # cap
    cyl(m, "$follower", (0, TOP + 0.3, cz), (0, TOP + TOOL, cz), PILE_R * 0.98, n=20, tag="orange")
    for y in (TOP + 1.2, TOP + 2.4):
        cyl(m, "$follower", (0, y, cz), (0, y + 0.1, cz), PILE_R * 1.0, n=20, tag="paint2")
    joints += [
        J("$pile", (0, 0, 0), (0, 1, 0), mode="slide", channel="pile_top", offset=round(-TOP, 4)),
        J("$pile_spin", (0, 0, cz), (0, 1, 0), mode="spin", channel="rpm", factor=0.3, parent="$pile"),
        J("$pile_shaft", (0, TOP, cz), (0, 1, 0), mode="scale", channel="pile_length", factor=round(1 / shaft, 5),
          offset=round(-tip_h / shaft, 5), min=0.02, parent="$pile_spin"),
        J("$pile_tip", (0, 0, 0), (0, 1, 0), mode="slide", channel="pile_length", factor=-1.0, offset=round(TOP, 4),
          parent="$pile_spin"),
        J("$follower", (0, hide, cz), (0, 1, 0), mode="show", channel="tool", parent="$pile_spin"),
    ]
    return joints


SETTINGS = {"head_min": GRIP, "head_max": GRIP + STROKE, "chuck_length": CHUCK_LEN, "tool_length": TOOL,
            "pile_length_min": 4, "pile_length_max": 10, "default_pile_length": 6}


def crawler_type():
    m = model("full_rotation_crawler_detail")
    cz = 3.65
    tracks = []
    for s in (1, -1):
        tracks.append(crawler(m, "l" if s > 0 else "r", s * 0.9, s * 1.65, 2.1, 0.85, r_end=0.36, wheel_r=0.15,
                              n_wheels=7, carrier_r=0.1, n_carrier=2, link_len=0.21))
    cbox(m, "body", -0.9, 0.3, -2.1, 0.9, 0.85, 2.1, "paint2", c=0.05)
    cbox(m, "body", -1.6, 0.85, -2.6, 1.6, 1.12, 2.0, "paint", c=0.04)                          # deck
    cbox(m, "body", -1.55, 1.12, -2.6, -0.1, 2.1, -0.7, "paint", c=0.06)                        # power pack
    for z in np.linspace(-2.45, -0.85, 7):
        box(m, "body", -1.56, 1.35, z - 0.04, -1.55, 1.9, z + 0.04, "grille")
    cbox(m, "body", -1.55, 1.12, -2.95, 1.55, 1.85, -2.6, "paint2", c=0.06)                     # counterweight
    box(m, "body", -1.55, 1.15, -2.97, 1.55, 1.3, -2.95, "stripe")
    cyl(m, "body", (-1.2, 2.1, -1.0), (-1.2, 2.55, -1.0), 0.07, n=8, tag="black")             # exhaust
    CAB = (0.25, 1.45, 1.12, 2.75, -1.1, 0.5)
    cab(m, "body", *CAB, door_side=1, seat=(0.85, 0, 0), front_slope=0.08)
    # frame carrying the unit off the front of the base
    for x in (-1.0, 1.0):
        bar(m, "body", (x, 0.75, 1.6), (x * 1.05, 0.75, cz - 1.25), 0.3, 0.35, "paint2")
        cyl(m, "body", (x - 0.15, 0.62, 1.85), (x + 0.15, 0.62, 1.85), 0.1, n=8, tag="steel")
    joints = unit(m, cz, 0.4, leg_lift=0.38)
    spec = {
        "top": {**cab_rider(*CAB, (0.85, 0, 0), 0.08)[2], "width": 3.4, "height": 2.8},
        "construction": {"joints": joints, "work_points": {"pile_point": pt(None, (0, 0.5, cz))}},
        "settings": {"pile_driver": dict(SETTINGS)},
    }
    merge_crawlers(spec, tracks)
    return m, spec, ()


def stationary_type():
    m = model("full_rotation_unit_detail")
    joints = unit(m, 0.0, 0.4)
    # operator platform on the -z side (facing the pile), with rails and steps
    cbox(m, "body", -0.6, 1.08, -2.6, 0.6, 1.15, -1.48, "steel", c=0.01)
    for x in (-0.55, 0.55):
        for z in (-2.55, -1.6):
            cyl(m, "body", (x, 0.0, z), (x, 1.08, z), 0.04, n=6, tag="paint2")
    guard_rails(m, "body", -0.6, 0.6, 1.15, -2.6, -1.48, h=1.0, tag="paint", sides=("l", "r", "b"), gate=None)
    for i in range(4):                                                                             # ladder
        y = 0.25 + i * 0.25
        box(m, "body", 0.6, y, -2.3, 0.85, y + 0.04, -1.9, "steel")
    s = open_scale(-0.56, 0.56, -2.56, -1.5)
    st = open_seat(m, "body", 1.15, -0.56, 0.56, -2.56, -1.5, 0.0, s, "operator", True, eye_over=1.2)
    cbox(m, "body", -0.35, 1.15, -1.62, 0.35, 1.95, -1.5, "paint2", c=0.02)                     # console
    cbox(m, "body", -0.3, 1.9, -1.66, 0.3, 2.1, -1.5, "black", c=0.01)
    # power pack beside it, hoses to the unit
    cbox(m, "body", 1.9, 0.0, -1.2, 3.1, 1.35, 1.0, "paint", c=0.06)
    for z in np.linspace(-1.0, 0.8, 8):
        box(m, "body", 3.1, 0.35, z - 0.04, 3.11, 1.1, z + 0.04, "grille")
    for z in (-0.3, 0.0, 0.3):
        tube(m, "body", [(1.9, 0.9, z), (1.6, 0.5, z), (1.45, 0.9, z * 0.8)], 0.035, tag="black")
    spec = {
        "top": {**riders_json([st], s), "width": 3.0, "height": 2.2},
        "construction": {"joints": joints, "work_points": {"pile_point": pt(None, (0, 0.5, 0.0))}},
        "settings": {"pile_driver": dict(SETTINGS)},
    }
    return m, spec, ()
