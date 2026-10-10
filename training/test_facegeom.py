import math
import unittest

import facegeom


def project(h, p):
    w = h[2][0] * p[0] + h[2][1] * p[1] + h[2][2]
    return ((h[0][0] * p[0] + h[0][1] * p[1] + h[0][2]) / w, (h[1][0] * p[0] + h[1][1] * p[1] + h[1][2]) / w)


class FaceGeomTest(unittest.TestCase):
    H = [[3.1, 0.4, 1500.0], [-0.2, 2.6, 1100.0], [0.00025, 0.00040, 1.0]]   # the same camera as in the Kotlin test

    def test_radial_distances(self):
        r = 120.0                                                          # blue edge of a 40 cm face
        center = project(self.H, (0.0, 0.0))
        edge = [project(self.H, (r * math.cos(math.radians(d)), r * math.sin(math.radians(d)))) for d in (10, 80, 150, 200, 290, 340)]
        g = facegeom.fit(center, edge)
        self.assertIsNotNone(g)
        for mm, deg in ((5, 30), (25, 200), (61, 100), (119, 300), (199, 45)):
            t = math.radians(deg)
            u = g.to_face(project(self.H, (mm * math.cos(t), mm * math.sin(t))))
            self.assertAlmostEqual(mm / r, math.hypot(*u), delta=0.003)
        back = g.to_image(g.to_face((1400.0, 1000.0)))
        self.assertAlmostEqual(1400.0, back[0], places=6); self.assertAlmostEqual(1000.0, back[1], places=6)

    def test_rings(self):
        at = lambda mm: facegeom.ring((mm / 120.0, 0.0), 400)
        self.assertEqual(facegeom.X, at(12)); self.assertEqual(10, at(22)); self.assertEqual(9, at(24))
        self.assertEqual(5, at(119)); self.assertEqual(1, at(199)); self.assertEqual(facegeom.MISS, at(204))
        self.assertEqual(facegeom.MISS, facegeom.ring((105 / 120.0, 0.0), 400, min_ring=6))

    def test_face_keypoints(self):
        r = 120.0
        center = project(self.H, (0.0, 0.0))
        edge = [project(self.H, (r * math.cos(math.radians(d)), r * math.sin(math.radians(d)))) for d in (10, 80, 150, 200, 290, 340)]
        g = facegeom.fit(center, edge)
        k = facegeom.face_keypoints(g)
        self.assertEqual(5, len(k))
        self.assertAlmostEqual(center[0], k[0][0], places=6); self.assertAlmostEqual(center[1], k[0][1], places=6)
        c, top, right, bottom, left = k
        self.assertLess(top[1], c[1]); self.assertGreater(bottom[1], c[1]); self.assertGreater(right[0], c[0]); self.assertLess(left[0], c[0])
        for p in k[1:]:                                                     # all four on the blue edge
            self.assertAlmostEqual(1.0, math.hypot(*g.to_face(p)), delta=0.002)
        # the four points alone give the face back (what the app does with the model's proposal)
        g2 = facegeom.fit(c, [top, right, bottom, left])
        self.assertIsNotNone(g2)
        u = g2.to_face(project(self.H, (60.0, 0.0)))
        self.assertLess(abs(math.hypot(*u) - 0.5), 0.03)
        x0, y0, x1, y1 = facegeom.face_box(k, 4000, 3000)
        self.assertLess(x0, left[0]); self.assertGreater(x1, right[0]); self.assertLess(y0, top[1]); self.assertGreater(y1, bottom[1])

    def test_record_with_four_edge_points(self):
        c = (640.0, 900.0); r = 420.0
        rec = {"center": list(c), "edge": [[c[0] + r, c[1]], [c[0], c[1] + r], [c[0] - r, c[1]], [c[0], c[1] - r]]}
        self.assertIsNotNone(facegeom.geometry_of_record(rec))
        self.assertIsNone(facegeom.geometry_of_record({"center": list(c), "edge": rec["edge"][:3]}))

    def test_four_points(self):
        c = (640.0, 900.0); r = 420.0
        taps = [(c[0] + r + 3, c[1] - 2), (c[0] - 1, c[1] + r + 2), (c[0] - r - 2, c[1] + 1), (c[0] + 2, c[1] - r + 3)]
        g = facegeom.fit(c, taps)
        self.assertIsNotNone(g)
        u = g.to_face((c[0] + r / 2, c[1]))
        self.assertLess(abs(math.hypot(*u) - 0.5), 0.02)

    def test_rejects(self):
        self.assertIsNone(facegeom.fit((0, 0), [(1, 0), (0, 1), (-1, 0)]))
        self.assertIsNone(facegeom.fit((0, 0), [(1, 0), (2, 0), (3, 0), (4, 0), (5, 0)]))


if __name__ == "__main__":
    unittest.main()
