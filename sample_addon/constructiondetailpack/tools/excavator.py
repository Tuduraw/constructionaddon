"""Hydraulic excavators: a 20 t class backhoe and a 3 t class mini backhoe (original designs).

Class reference (general published ranges, compared for structure and size only):
  20 t class: crawler length ~4.4 m, overall width ~2.8 m (shoe 0.6 m), cab roof ~3.0 m,
              tail swing radius ~2.7-2.8 m, boom ~5.7 m, arm ~2.9 m, max reach ~9.9 m.
  3 t class:  crawler length ~2.1 m, width ~1.5 m, height ~2.5 m, short-tail upper,
              boom ~2.6 m, arm ~1.3 m, dozer blade in front.
"""
import numpy as np

from common import (cab_rider, box, cbox, cyl, bar, side_plate, plan_plate, ellipse_xz, tube, lamp, cab, crawler, merge_crawlers, actuator,
                    model, mk, mech, unit, J, pt, seat)

LARGE = dict(
    name="backhoe", X=-0.05, track=(0.8, 1.4), z_half=2.22, track_top=0.92, crawler=dict(r_end=0.39, wheel_r=0.13, n_wheels=7, carrier_r=0.09, n_carrier=2, link_len=0.19),
    swing_y=1.0, hw=1.35, front=1.75, tail=2.75, deck=(1.05, 1.25), cw_top=2.2, hood_top=2.3,
    cab=(0.35, 1.35, 1.25, 3.0, 0.45, 2.15), seat=(0.85, 1.3, 1.25),
    B=(1.75, 1.4), M1=(4.65, 3.3), A=(5.35, 5.8), K=(2.75, 7.1), T=(1.4, 7.65),
    boom_hw=0.3, boom_d=(0.38, 0.46, 0.3), arm_hw=0.24, bucket_w=1.0, ram_r=(0.14, 0.12, 0.11),
    blade=None, k=1.0,
)
MINI = dict(
    name="mini_backhoe", X=-0.38, track=(0.45, 0.75), z_half=1.05, track_top=0.5, crawler=dict(r_end=0.18, wheel_r=0.065, n_wheels=4, link_len=0.12, shoe_t=0.05),
    swing_y=0.5, hw=0.75, front=0.75, tail=0.85, deck=(0.52, 0.65), cw_top=1.15, hood_top=1.15,
    cab=(-0.08, 0.72, 0.65, 2.45, -0.62, 0.55), seat=(0.32, 0.7, -0.1),
    B=(0.85, 0.75), M1=(1.95, 1.15), A=(2.75, 2.45), K=(1.52, 3.0), T=(0.84, 3.3),
    boom_hw=0.13, boom_d=(0.17, 0.2, 0.13), arm_hw=0.1, bucket_w=0.45, ram_r=(0.07, 0.06, 0.055),
    blade=(1.08, 1.5, 0.36), k=0.5,
)


def up(d):
    n = unit(np.cross([1, 0, 0], d))
    return n if n[1] > 0 else -n


def build(C):
    m = model(C["name"] + "_detail")
    X, k = C["X"], C["k"]
    P = lambda yz: np.array([X, yz[0], yz[1]], float)
    B, M1, A, K, T = (P(C[n]) for n in ("B", "M1", "A", "K", "T"))
    sy = C["swing_y"]
    xi, xo = C["track"]
    joints = []

    # ------------------------------------------------------------ lower body
    tracks = [crawler(m, side, s * xi, s * xo, C["z_half"], C["track_top"], **C["crawler"])
              for side, s in (("l", 1), ("r", -1))]
    cbox(m, "body", -xi - 0.02, sy * 0.3, -C["z_half"] * 0.5, xi + 0.02, sy - 0.02, C["z_half"] * 0.5, "paint2",
         c=0.08 * k)
    for z in (-C["z_half"] * 0.32, C["z_half"] * 0.32):
        bar(m, "body", (-xi - 0.05, sy * 0.55, z), (xi + 0.05, sy * 0.55, z), 0.4 * k + 0.1, 0.3 * k + 0.1, "paint2")
    cyl(m, "body", (0, sy - 0.12, 0), (0, sy + 0.1, 0), C["hw"] * 0.62, n=20, tag="paint2")   # swing bearing
    if C["blade"]:
        zb, wb, hb = C["blade"]
        for s in (1, -1):
            bar(m, "body", (s * 0.45, 0.3, 0.6), (s * 0.55, 0.22, zb), 0.1, 0.12, "paint")
        side_plate(m, "body", [(zb, 0.0), (zb + 0.12, 0.0), (zb + 0.14, hb), (zb + 0.04, hb)], -wb / 2, wb / 2, "paint")
        box(m, "body", -wb / 2, 0.0, zb + 0.1, wb / 2, 0.06, zb + 0.16, "steel")
        cyl(m, "body", (0, 0.42, 0.55), (0, 0.3, zb - 0.02), 0.045, n=6, tag="paint")

    # ------------------------------------------------------------ upper structure
    U = "$upper"
    hw, fr, tail = C["hw"], C["front"], C["tail"]
    y0, y1 = C["deck"]
    tail_pts = [(x, z) for x, z in ellipse_xz(0, 0, hw, tail, 200, 340, 9)]
    deck = [(hw, fr), (-hw, fr), (-hw, tail_pts[0][1])] + tail_pts + [(hw, tail_pts[-1][1])]
    plan_plate(m, U, deck, y0, y1, "paint2")
    # counterweight (rear arc) with a hazard band
    zc = -tail * 0.62
    cw = [(x, z) for x, z in ellipse_xz(0, zc, hw, tail - abs(zc), 180, 360, 13)]
    plan_plate(m, U, cw, y1 - 0.05, C["cw_top"], "paint")
    plan_plate(m, U, [(x * 0.97, z * 0.99 + zc * 0.01) for x, z in cw], C["cw_top"] - 0.02, C["cw_top"] + 0.06 * k,
               "paint")
    plan_plate(m, U, [(x * 1.004, z * 1.002) for x, z in cw], y1 + 0.02, y1 + 0.2 * k + 0.05, "stripe")
    cx0, cx1, cy0, cy1, cz0, cz1 = C["cab"]
    if k > 0.8:
        # engine hood across the rear-right, tank behind the cab, boom foot cheeks
        hood = [(zc, y1), (cz1 - 0.1, y1), (cz1 - 0.1, C["hood_top"] - 0.4), (cz1 - 0.45, C["hood_top"] - 0.1),
                (zc, C["hood_top"])]
        side_plate(m, U, hood, -hw, X - 0.45, "paint")
        side_plate(m, U, [(zc, y1), (cz0, y1), (cz0, C["hood_top"]), (zc, C["hood_top"])], X - 0.47, hw, "paint")
        for i in range(9):
            z = zc + 0.15 + i * 0.2
            box(m, U, -hw - 0.02, y1 + 0.2, z, -hw + 0.01, C["hood_top"] - 0.2, z + 0.1, "grille")
        for i in range(6):
            z = zc + 0.1 + i * 0.25
            box(m, U, -hw + 0.15, C["hood_top"] - 0.01, z, X - 0.6, C["hood_top"] + 0.01, z + 0.14, "grille")
        cyl(m, U, (-0.9, C["hood_top"], zc + 0.5), (-0.9, C["hood_top"] + 0.55, zc + 0.5), 0.08, n=8, tag="black")
        cyl(m, U, (-0.55, C["hood_top"], cz0 - 0.3), (-0.55, C["hood_top"] + 0.25, cz0 - 0.3), 0.17, n=10,
            tag="paint2")
        tube(m, U, [(-hw + 0.08, C["hood_top"], zc + 0.2), (-hw + 0.08, C["hood_top"] + 0.4, zc + 0.3),
                    (-hw + 0.08, C["hood_top"] + 0.4, cz1 - 0.6), (-hw + 0.08, C["hood_top"] - 0.1, cz1 - 0.3)], 0.03)
        cbox(m, U, cx0, y1, cz0 - 1.3, hw, C["hood_top"] - 0.15, cz0 - 0.05, "paint", c=0.05)   # hydraulic tank
        box(m, U, X - 0.47, y1, cz0 - 0.1, cx0 - 0.02, y1 + 0.3, fr, "paint2")                # right deck walkway
    else:
        # mini: engine under the seat, rounded rear cover
        plan_plate(m, U, [(x * 0.9, z * 0.9) for x, z in cw[2:-2]] + [(-hw * 0.88, 0.0), (hw * 0.88, 0.0)],
                   y1, C["cw_top"] + 0.15, "paint")
    cab(m, U, cx0, cx1, cy0, cy1, cz0, cz1, door_side=1, seat=C["seat"], front_slope=0.1 * k)
    lamp(m, U, (cx0 + cx1) / 2, cy1 + 0.1 * k, cz1 - 0.05, 0.07 * max(k, 0.7))
    if k > 0.8:
        bar(m, U, (cx1 + 0.02, cy1 - 1.0, cz1 - 0.1), (cx1 + 0.2, cy1 - 0.6, cz1), 0.04, 0.04, "black")
        box(m, U, cx1 + 0.14, cy1 - 0.7, cz1 - 0.05, cx1 + 0.26, cy1 - 0.4, cz1 + 0.05, "black")
    # boom foot bracket
    bw = C["boom_hw"]
    for x0 in (X - bw - 0.12 * k - 0.02, X + bw + 0.02):
        side_plate(m, U, [(B[2] - 0.75 * k - 0.1, y1 - 0.05), (B[2] + 0.3 * k, y1 - 0.05), (B[2] + 0.3 * k, B[1]),
                          (B[2] + 0.05, B[1] + 0.3 * k), (B[2] - 0.5 * k, B[1] + 0.15 * k)],
                   x0, x0 + 0.1 * k + 0.02, "paint")
    if k < 0.8:  # mini: swing post in front of the deck
        cbox(m, U, X - 0.22, y0, B[2] - 0.35, X + 0.22, B[1] + 0.08, B[2] + 0.48, "paint", c=0.03)

    # ------------------------------------------------------------ boom
    d0, d1, d2 = C["boom_d"]
    m.add("$boom", mech.seg([B, M1, A], [(bw, d0, 0.05 * k), (bw, d1, 0.05 * k), (bw * 0.85, d2, 0.04 * k)],
                            tag="paint", side=(1, 0, 0)))
    cyl(m, "$boom", B + [-bw - 0.15, 0, 0], B + [bw + 0.15, 0, 0], d0 * 0.35, n=10, tag="steel")
    for x0 in (X - bw * 0.85 - 0.06 * k, X + bw * 0.85):
        side_plate(m, "$boom", [(A[2] - 0.6 * k - 0.05, A[1] - 0.3 * k), (A[2] + 0.25 * k, A[1] - 0.35 * k),
                                (A[2] + 0.3 * k, A[1] + 0.25 * k), (A[2] - 0.35 * k, A[1] + 0.35 * k)],
                   x0, x0 + 0.06 * k, "paint")
    for dx in (-0.4, 0.4):
        tube(m, "$boom", [B + [dx * bw, d0 * 0.95, -0.2 * k], M1 + [dx * bw, d1 * 0.95, 0.0],
                          A + [dx * bw, d2 + 0.05, -0.4 * k]], 0.03 * k + 0.01, tag="black", n=5)
    rb, ra, rk = C["ram_r"]
    # boom ram: deck in front of the foot -> underside of the boom (two rams on the large machine)
    base = B + np.array([0, -(B[1] - y1) * 0.55, 0.45 * k + 0.02])
    att = B + 0.82 * (M1 - B) - up(M1 - B) * (d1 + rb)
    cbox(m, U, -rb * 1.6, y1 - 0.02, base[2] - rb * 1.8, rb * 1.6, base[1] + rb * 0.6, base[2] + rb * 1.4, "paint2",
         c=0.02)                                                                  # ram foot bracket on the deck
    j, e1 = actuator(m, "boom_ram", U, base, att, "$boom", B, (-1, 0, 0), -40, 35, rb)
    joints_rams = list(j)
    # ------------------------------------------------------------ arm
    E = A + np.array([0, 0.62, -0.52]) * k
    aw = C["arm_hw"]
    m.add("$arm", mech.seg([E, A, K + [0, 0.12 * k, -0.05 * k]], [(aw * 0.9, 0.2 * k, 0.04 * k),
                                                                  (aw, 0.3 * k + 0.04, 0.05 * k),
                                                                  (aw * 0.75, 0.18 * k, 0.04 * k)],
                           tag="paint", side=(1, 0, 0)))
    cyl(m, "$arm", A + [-bw - 0.05, 0, 0], A + [bw + 0.05, 0, 0], 0.12 * k, n=10, tag="steel")
    base = B + 0.85 * (M1 - B) + up(M1 - B) * (d0 + ra + 0.05 * k)
    j, e2 = actuator(m, "arm_ram", "$boom", base, E, "$arm", A, (-1, 0, 0), -45, 75, ra)
    joints_rams += j
    bar(m, "$boom", base - up(M1 - B) * (d0 + 0.05), base, 0.18 * k + 0.04, 0.24 * k, "paint", up=(0, 0, 1))
    link = K + up(K - E) * 0.42 * k + unit(K - E) * -0.05 * k
    base = E + unit(K - E) * 0.3 * k + up(K - E) * (0.2 * k + rk)
    j, e3 = actuator(m, "bucket_ram", "$arm", base, link, "$bucket", K, (1, 0, 0), -60, 100, rk)
    joints_rams += j
    for x0 in (X - aw - 0.05 * k, X + aw):
        side_plate(m, "$arm", [(K[2] - 0.5 * k, K[1] + 0.3 * k), (K[2] - 0.2 * k, K[1] + 0.1 * k),
                               (K[2] + 0.05 * k, K[1] + 0.15 * k), (K[2] - 0.15 * k, K[1] + 0.48 * k)],
                   x0, x0 + 0.05 * k, "paint2")

    # ------------------------------------------------------------ bucket
    Bk = "$bucket"
    w = C["bucket_w"] / 2
    v = T - K; L = np.linalg.norm(v); u = v / L
    n = unit(np.cross([1, 0, 0], u)) * (1 if np.cross([1, 0, 0], u)[2] > 0 else -1)
    shell = [K - u * 0.05 * L + n * 0.05 * L, K + u * 0.25 * L + n * 0.42 * L, K + u * 0.62 * L + n * 0.45 * L,
             K + u * 0.9 * L + n * 0.25 * L, T + u * 0.02 * L]
    for a, b in zip(shell, shell[1:]):
        bar(m, Bk, a, b, 2 * w, 0.05 * k + 0.01, "paint", up=-np.cross([1, 0, 0], unit(b - a)))
    prof = [(p[2], p[1]) for p in shell] + [(K[2] + u[2] * 0.35 * L - n[2] * 0.05 * L,
                                              K[1] + u[1] * 0.35 * L - n[1] * 0.05 * L)]
    for x0 in (X - w - 0.02, X + w - 0.02):
        side_plate(m, Bk, prof, x0, x0 + 0.04, "paint")
    bar(m, Bk, T + [-w, 0, 0], T + [w, 0, 0], 0.1 * k + 0.02, 0.06 * k + 0.02, "steel")
    nt = 5 if k > 0.8 else 4
    for i in range(nt):
        x = X - w + 0.08 + i * (2 * w - 0.16) / (nt - 1)
        root = T + np.array([x - X, 0, 0])
        m.add(Bk, mech.claw(root, root + u * 0.2 * k + n * -0.02, 0.05 * k + 0.01, 0.035 * k + 0.01, "steel"))
    for x0 in (X - aw * 0.8 - 0.06 * k, X + aw * 0.8):
        side_plate(m, Bk, [(K[2] - 0.15 * k, K[1] - 0.05 * k), (K[2] + 0.2 * k, K[1] - 0.2 * k),
                           (K[2] + 0.25 * k, K[1] + 0.25 * k), (K[2] - 0.1 * k, K[1] + 0.2 * k)],
                   x0, x0 + 0.06 * k, "paint2")
    cyl(m, Bk, K + [-aw - 0.05, 0, 0], K + [aw + 0.05, 0, 0], 0.08 * k + 0.01, n=8, tag="steel")
    m.add("$bucket_load", mk.extrude_zy([(p[2], p[1]) for p in [K + u * 0.3 * L + n * 0.3 * L,
                                                                K + u * 0.6 * L + n * 0.38 * L,
                                                                K + u * 0.82 * L + n * 0.2 * L,
                                                                K + u * 0.62 * L - n * 0.12 * L,
                                                                K + u * 0.32 * L - n * 0.08 * L]],
                                        X - w + 0.04, X + w - 0.04, tag="earth"))

    joints = [
        J(U, (0, sy, 0), (0, 1, 0), channel="swing"),
        J("$boom", B, (-1, 0, 0), channel="boom", parent=U),
        J("$arm", A, (-1, 0, 0), channel="arm", parent="$boom"),
        J("$bucket", K, (1, 0, 0), channel="bucket", parent="$arm"),
        J("$bucket_load", K + u * 0.45 * L, (0, 1, 0), mode="scale", channel="load", parent="$bucket", offset=0.0),
    ] + joints_rams
    spec = {
        "top": cab_rider(cx0, cx1, cy0, cy1, cz0, cz1, C["seat"], 0.1 * k)[2],
        "construction": {"joints": joints, "work_points": {"bucket_tip": pt("$bucket", T)},
                         "seat_parts": [{"seat": 0, "part": U}]},
        "settings": {"excavator": {"arm_min": -45.0, "arm_max": 75.0, "bucket_min": -60.0, "bucket_max": 100.0,
                                   "dig_radius": round(0.55 + 0.45 * k, 2)}},
    }
    merge_crawlers(spec, tracks)
    return m, spec, (e1, e2, e3)


def backhoe():
    m, spec, e = build(LARGE)
    spec["top"].update({"width": 3.0, "height": 3.1})
    spec["settings"]["excavator"]["capacity"] = 6
    return m, spec, e


def mini_backhoe():
    m, spec, e = build(MINI)
    spec["top"].update({"scale": 1.0, "width": 1.6, "height": 2.5, "step_height": 0.6})
    return m, spec, e
