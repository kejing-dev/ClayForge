"""Prepares the Tripo props for ClayForge (run with ~/.factory-venv/bin/python, after bin/glb_decimate.py).

tools-dec.glb -> tool_add / tool_carve / tool_smooth / tool_pull.glb : split along x gaps, head tip at the origin,
                 handle along +x, length 0.11 m (design: knife head toward -x of its own frame).
kiln-dec.glb  -> kiln_body.glb (open tube, warped to a true cylinder r 0.18, y 0..0.37) + kiln_lid.glb (dome,
                 bottom at y 0, base r 0.18), cut at the lid seam.
jar-dec.glb   -> jar.glb : D 0.062 x 0.06, base centre at the origin.
Prints a calibration table for ui-audit (D25).
"""
import sys, os, numpy as np, trimesh
src, dst = sys.argv[1], sys.argv[2]

def load(n):
    m = trimesh.load(os.path.join(src, n), force='mesh', process=False)
    return m

def smooth_normals(m, cos_lim=0.6):
    fn = m.face_normals
    key = np.round(m.vertices, 5)
    _, inv = np.unique(key, axis=0, return_inverse=True)
    inv = inv.reshape(-1)
    vf = np.repeat(np.arange(len(m.faces)), 3)
    vv = m.faces.reshape(-1)
    acc = np.zeros((inv.max() + 1, 3))
    np.add.at(acc, inv[vv], fn[vf] * m.area_faces[vf, None])
    n = acc[inv]
    own = np.zeros((len(m.vertices), 3)); own[vv] = fn[vf]
    n = n / (np.linalg.norm(n, axis=1, keepdims=True) + 1e-12)
    bad = (n * own).sum(1) < cos_lim
    n[bad] = own[bad]
    return n

def sub(m, mask):
    s = m.submesh([np.nonzero(mask)[0]], append=True, repair=False)
    return s

def save(m, name, verts=None):
    if verts is not None:
        m = trimesh.Trimesh(verts, m.faces, visual=m.visual, process=False)
    vn = smooth_normals(m)
    out = trimesh.Trimesh(m.vertices, m.faces, vertex_normals=vn, visual=m.visual, process=False)
    out.export(os.path.join(dst, name))
    e = out.bounds
    print(f'{name}: tris={len(out.faces)} min={np.round(e[0],4).tolist()} max={np.round(e[1],4).tolist()} kb={os.path.getsize(os.path.join(dst,name))//1024}')
    return out

# ---------------- tools
t = load('tools-dec.glb')
cx = t.triangles_center[:, 0]
hist, edges = np.histogram(cx, bins=200)
empty = hist == 0
# gaps = runs of empty bins; take the 3 widest
runs, i = [], 0
while i < len(empty):
    if empty[i]:
        j = i
        while j < len(empty) and empty[j]: j += 1
        runs.append((j - i, (edges[i] + edges[j]) / 2)); i = j
    else: i += 1
cuts = sorted([c for _, c in sorted(runs, reverse=True)[:3]])
print('tool cuts', np.round(cuts, 3))
bounds = [-1e9] + cuts + [1e9]
names = ['add', 'carve', 'smooth', 'pull']
for k, n in enumerate(names):
    m = sub(t, (cx > bounds[k]) & (cx < bounds[k + 1]))
    v = m.vertices.copy()
    ymin, ymax = v[:, 1].min(), v[:, 1].max()
    s = 0.11 / (ymax - ymin)
    c = (v[:, 0].max() + v[:, 0].min()) / 2; cz = (v[:, 2].max() + v[:, 2].min()) / 2
    # rotate +90 deg about z: head (+y) -> -x; head tip at the origin, handle along +x
    nv = np.stack([(ymax - v[:, 1]) * s, (v[:, 0] - c) * s, (v[:, 2] - cz) * s], 1)
    save(m, f'tool_{n}.glb', nv)

# ---------------- kiln
k = load('kiln-dec.glb')
v = k.vertices; y = v[:, 1]
y0, y1 = y.min(), y.max(); H = y1 - y0
cy = k.triangles_center[:, 1]
cxz = (v[:, [0, 2]].max(0) + v[:, [0, 2]].min(0)) / 2
# lid seam: the narrowest-waist step near 70-75 % height -> find radius profile and the biggest jump
r = np.linalg.norm(v[:, [0, 2]] - cxz, axis=1)
bands = np.linspace(y0, y1, 61)
prof = np.array([np.percentile(r[(y >= bands[i]) & (y < bands[i + 1])], 95) if ((y >= bands[i]) & (y < bands[i + 1])).sum() > 3 else 0 for i in range(60)])
# the vent-ring seam sits at ~65 % height (Tripo render, apps/clay-forge/design/tripo-kiln.jpg); take the biggest
# radius step within 60-70 %
lo, hi = int(60 * 0.60), int(60 * 0.70)
jump = np.argmax(np.abs(np.diff(prof[lo:hi]))) + lo + 1
seam = bands[jump]
print('kiln seam at %.1f %%' % ((seam - y0) / H * 100), 'profile', np.round(prof / prof.max(), 2).tolist())
feet = y0 + 0.05 * H
body = sub(k, (cy < seam) & (cy > feet))
lid = sub(k, cy >= seam)
# body: warp each height band to a true cylinder r 0.18 (keeps rings / latch relief), y 0..0.37
bv = body.vertices.copy(); by = bv[:, 1]
br = np.linalg.norm(bv[:, [0, 2]] - cxz, axis=1)
pb = np.interp(by, (bands[:-1] + bands[1:]) / 2, prof)
pb = np.maximum(pb, 1e-6)
scale = 0.18 / pb
nx = (bv[:, 0] - cxz[0]) * scale; nz = (bv[:, 2] - cxz[1]) * scale
ny = (by - by.min()) / (by.max() - by.min()) * 0.37
save(body, 'kiln_body.glb', np.stack([nx, ny, nz], 1))
lv = lid.vertices.copy()
lr = prof[jump:].max() if jump < len(prof) else prof.max()
ls = 0.18 / np.percentile(np.linalg.norm(lv[:, [0, 2]] - cxz, axis=1), 99)
lh = (lv[:, 1].max() - lv[:, 1].min()) * ls
ys = min(1.0, 0.09 / lh) if lh > 0.09 else 1.0
save(lid, 'kiln_lid.glb', np.stack([(lv[:, 0] - cxz[0]) * ls, (lv[:, 1] - lv[:, 1].min()) * ls * ys, (lv[:, 2] - cxz[1]) * ls], 1))

# ---------------- jar
j = load('jar-dec.glb')
v = j.vertices.copy()
w = max(v[:, 0].max() - v[:, 0].min(), v[:, 2].max() - v[:, 2].min()); h = v[:, 1].max() - v[:, 1].min()
c = (v.max(0) + v.min(0)) / 2
nv = np.stack([(v[:, 0] - c[0]) * 0.062 / w, (v[:, 1] - v[:, 1].min()) * 0.06 / h, (v[:, 2] - c[2]) * 0.062 / w], 1)
save(j, 'jar.glb', nv)
