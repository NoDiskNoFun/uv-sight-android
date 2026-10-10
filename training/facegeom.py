"""Face geometry of the UV-Sight app, ported 1:1 from core/Face.kt (no numpy needed).

fit(center, edge)      -> FaceGeometry or None   (image pixels -> unit face, blue edge radius = 1)
ring(u, diameter_mm, min_ring, arrow_mm) -> 11 = X, 10..1, 0 = miss
"""
import math

X, MISS = 11, 0
FACES = {"WA40": (400, 1), "WA60": (600, 1), "WA80": (800, 1), "WA122": (1220, 1), "SPOT40": (400, 6)}


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _transpose(a):
    return [[a[j][i] for j in range(3)] for i in range(3)]


def _inverse(a):
    c = [[0.0] * 3 for _ in range(3)]
    c[0][0] = a[1][1] * a[2][2] - a[1][2] * a[2][1]; c[0][1] = -(a[1][0] * a[2][2] - a[1][2] * a[2][0]); c[0][2] = a[1][0] * a[2][1] - a[1][1] * a[2][0]
    c[1][0] = -(a[0][1] * a[2][2] - a[0][2] * a[2][1]); c[1][1] = a[0][0] * a[2][2] - a[0][2] * a[2][0]; c[1][2] = -(a[0][0] * a[2][1] - a[0][1] * a[2][0])
    c[2][0] = a[0][1] * a[1][2] - a[0][2] * a[1][1]; c[2][1] = -(a[0][0] * a[1][2] - a[0][2] * a[1][0]); c[2][2] = a[0][0] * a[1][1] - a[0][1] * a[1][0]
    det = a[0][0] * c[0][0] + a[0][1] * c[0][1] + a[0][2] * c[0][2]
    if abs(det) < 1e-18:
        raise ValueError("singular")
    return [[c[j][i] / det for j in range(3)] for i in range(3)]


def _apply(m, p):
    w = m[2][0] * p[0] + m[2][1] * p[1] + m[2][2]
    return ((m[0][0] * p[0] + m[0][1] * p[1] + m[0][2]) / w, (m[1][0] * p[0] + m[1][1] * p[1] + m[1][2]) / w)


def _solve(a, b):
    n = len(b); a = [row[:] for row in a]; b = b[:]
    for c in range(n):
        piv = max(range(c, n), key=lambda r: abs(a[r][c]))
        if abs(a[piv][c]) < 1e-12:
            return None
        a[c], a[piv] = a[piv], a[c]; b[c], b[piv] = b[piv], b[c]
        for r in range(n):
            if r == c:
                continue
            f = a[r][c] / a[c][c]
            for k in range(c, n):
                a[r][k] -= f * a[c][k]
            b[r] -= f * b[c]
    return [b[i] / a[i][i] for i in range(n)]


def _sym_sqrt(s00, s01, s11):
    tr, det = s00 + s11, s00 * s11 - s01 * s01
    if det <= 0 or tr <= 0:
        return None
    disc = math.sqrt(max(0.0, tr * tr / 4 - det))
    l1, l2 = tr / 2 + disc, tr / 2 - disc
    if l2 <= 0:
        return None
    theta = 0.0 if abs(s01) < 1e-15 and abs(s00 - s11) < 1e-15 else 0.5 * math.atan2(2 * s01, s00 - s11)
    c, s = math.cos(theta), math.sin(theta)
    r1, r2 = math.sqrt(l1), math.sqrt(l2)
    return (c * c * r1 + s * s * r2, c * s * (r1 - r2), s * s * r1 + c * c * r2, r1 / r2)


class FaceGeometry:
    def __init__(self, to_face, conic):
        self._to_face = to_face
        self._to_image = _inverse(to_face)
        self.conic = conic

    def to_face(self, p):
        return _apply(self._to_face, p)

    def to_image(self, u):
        return _apply(self._to_image, u)


def fit(center, edge):
    """5+ edge points: free conic (handles a camera off to the side); 4 points, or a failed free fit:
    the tapped centre is taken as the ellipse centre. Mirrors FaceGeometry.fit in the app."""
    if len(edge) < 4:
        return None
    if len(edge) == 4 or _largest_gap(center, edge) > math.pi:
        return _fit_conic(center, edge, 2)          # centred and axis-aligned
    return _fit_conic(center, edge, 5) or _fit_conic(center, edge, 1)   # fallback: a circle around the centre


def _largest_gap(center, edge):
    ang = sorted(math.atan2(y - center[1], x - center[0]) for x, y in edge)
    gap = ang[0] + 2 * math.pi - ang[-1]
    for i in range(1, len(ang)):
        gap = max(gap, ang[i] - ang[i - 1])
    return gap


def _fit_conic(center, edge, n):
    centred = n != 5
    scale = sum(math.hypot(x - center[0], y - center[1]) for x, y in edge) / len(edge)
    if scale < 1e-6:
        return None
    t = [[1 / scale, 0.0, -center[0] / scale], [0.0, 1 / scale, -center[1] / scale], [0.0, 0.0, 1.0]]
    pts = [_apply(t, p) for p in edge]
    # a = 1/2 + a':  a'(x^2 - y^2) + b xy + d x + e y + f = -(x^2 + y^2)/2 ; ridge pulls towards a circle
    a = [[0.0] * n for _ in range(n)]; b = [0.0] * n
    for x, y in pts:
        row = {5: [x * x - y * y, x * y, x, y, 1.0], 2: [x * x - y * y, 1.0], 1: [1.0]}[n]; yy = -(x * x + y * y) / 2
        for i in range(n):
            b[i] += row[i] * yy
            for j in range(n):
                a[i][j] += row[i] * row[j]
    for i in range(n):
        a[i][i] += 0.01 if (n == 2 and i == 0) else 1e-9
    sol = _solve(a, b)
    if sol is None:
        return None
    ca = 0.5 if n == 1 else 0.5 + sol[0]; cc = 1 - ca
    cb = 0.0 if centred else sol[1]
    cd, ce, cf = (0.0, 0.0, sol[-1]) if centred else (sol[2], sol[3], sol[4])
    if cb * cb - 4 * ca * cc >= 0:
        return None
    cn = [[ca, cb / 2, cd / 2], [cb / 2, cc, ce / 2], [cd / 2, ce / 2, cf]]
    l = [cn[0][2], cn[1][2], cn[2][2]]
    if abs(l[2]) < 1e-12:
        return None
    h1 = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [l[0] / l[2], l[1] / l[2], 1.0]]
    h1i = _inverse(h1)
    c2 = _mul(_mul(_transpose(h1i), cn), h1i)
    gamma = c2[2][2]
    if gamma >= 0:
        return None
    m = _sym_sqrt(c2[0][0] / -gamma, c2[0][1] / -gamma, c2[1][1] / -gamma)
    if m is None:
        return None
    mm = [[m[0], m[1], 0.0], [m[1], m[2], 0.0], [0.0, 0.0, 1.0]]
    to_face = _mul(_mul(mm, h1), t)
    if any(abs(math.hypot(*_apply(to_face, p)) - 1) > 0.12 for p in edge) or m[3] > 3.0:
        return None
    cpix = _mul(_mul(_transpose(t), cn), t)
    conic = [cpix[0][0], 2 * cpix[0][1], cpix[1][1], 2 * cpix[0][2], 2 * cpix[1][2], cpix[2][2]]
    try:
        return FaceGeometry(to_face, conic)
    except ValueError:
        return None


def ring(u, diameter_mm, min_ring=1, arrow_mm=6.0):
    ring_mm = diameter_mm / 20.0
    r_mm = math.hypot(u[0], u[1]) * 6 * ring_mm
    edge = max(0.0, r_mm - arrow_mm / 2)
    rw = edge / ring_mm
    if rw >= 10:
        return MISS
    n = 10 - int(math.floor(rw))
    if n < min_ring:
        return MISS
    return X if (n == 10 and rw <= 0.5) else n


def mm_from_center(u, diameter_mm):
    r = 6 * diameter_mm / 20.0
    return (u[0] * r, -u[1] * r)


def geometry_of_record(rec):
    """FaceGeometry of a PhotoRecord (dict), or None if the face was not marked."""
    if not rec.get("center") or len(rec.get("edge") or []) < 4:
        return None
    return fit(tuple(rec["center"]), [tuple(p) for p in rec["edge"]])


FACE_KPT = ["centre", "top", "right", "bottom", "left"]


def face_keypoints(g, n=360):
    """The five face keypoints the face model learns, in image pixels: the centre and the points of
    the blue edge that are highest, rightmost, lowest and leftmost in the image. They are the same
    for every photo whatever the user tapped, which pose training needs."""
    pts = [g.to_image((math.cos(2 * math.pi * i / n), math.sin(2 * math.pi * i / n))) for i in range(n)]
    return [g.to_image((0.0, 0.0)), min(pts, key=lambda p: p[1]), max(pts, key=lambda p: p[0]), max(pts, key=lambda p: p[1]), min(pts, key=lambda p: p[0])]


def face_box(kpts, w, h, margin=0.04):
    """Bounding box (x0, y0, x1, y1) of the blue edge from its extreme points, a little wider, clipped to the image."""
    c, top, right, bottom, left = kpts
    bw, bh = right[0] - left[0], bottom[1] - top[1]
    return (max(0.0, left[0] - margin * bw), max(0.0, top[1] - margin * bh), min(float(w), right[0] + margin * bw), min(float(h), bottom[1] + margin * bh))
