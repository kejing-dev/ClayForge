package io.embodit.clayforge.domain

/**
 * The electric kiln's parts as plain meshes in bench coordinates (design assets.kiln, C5; door parts relative to the
 * hinge). Built in code instead of tools/make_kiln.py (same rounded boxes / tubes, exact sizes); every part is a closed
 * mesh (`kilnPartsAreWatertight`). Nothing is ever scaled (M18).
 */
object KilnMesh {
    private val L = Layout
    private val x0 = L.KILN_C.x - L.KILN_W / 2; private val x1 = L.KILN_C.x + L.KILN_W / 2
    private val z0 = L.KILN_C.z - L.KILN_D / 2; private val z1 = L.KILN_FRONT_Z
    private const val W = Layout.KILN_WALL

    private fun box(m: PMesh, a: V3, b: V3, r: Float, uv: Float = 1f, seg: Int = if (r >= 0.01f) 3 else if (r >= 0.004f) 2 else 1) =
        Proc.roundedBox(V3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2), V3((b.x - a.x) / 2, (b.y - a.y) / 2, (b.z - a.z) / 2), r, seg, uv, m)

    /** Painted steel walls: back, sides, roof, the jambs and the lintel round the door hole. */
    fun shell(): List<PMesh> = listOf(
        box(PMesh(), V3(x0, 0f, z0), V3(x1, L.KILN_H, z0 + W), 0.012f),
        box(PMesh(), V3(x0, 0f, z0), V3(x0 + W, L.KILN_H, z1), 0.012f),
        box(PMesh(), V3(x1 - W, 0f, z0), V3(x1, L.KILN_H, z1), 0.012f),
        box(PMesh(), V3(x0, L.KILN_IN_Y1, z0), V3(x1, L.KILN_H, z1), 0.012f),
        box(PMesh(), V3(x0, 0f, z1 - W), V3(L.KILN_HOLE_X0, L.KILN_H, z1), 0.008f),
        box(PMesh(), V3(L.KILN_HOLE_X1, 0f, z1 - W), V3(x1, L.KILN_H, z1), 0.008f),
        box(PMesh(), V3(L.KILN_HOLE_X0, L.KILN_HOLE_Y1, z1 - W), V3(L.KILN_HOLE_X1, L.KILN_H, z1), 0.006f),
    )

    /** Two stainless bands (y 0.06 / 0.46) proud of the shell by 3 mm. */
    fun bands(): List<PMesh> = listOf(0.06f, 0.46f).flatMap { y ->
        val a = y - 0.006f; val b = y + 0.006f; val o = 0.003f
        listOf(
            box(PMesh(), V3(x0 - o, a, z0 - o), V3(x1 + o, b, z0 + 0.01f), 0.002f),
            box(PMesh(), V3(x0 - o, a, z0 - o), V3(x0 + 0.01f, b, z1 + o), 0.002f),
            box(PMesh(), V3(x1 - 0.01f, a, z0 - o), V3(x1 + o, b, z1 + o), 0.002f),
        ) + if (y > L.KILN_HOLE_Y1) listOf(box(PMesh(), V3(x0 - o, a, z1 - 0.01f), V3(x1 + o, b, z1 + o), 0.002f)) else listOf(
            box(PMesh(), V3(x0 - o, a, z1 - 0.01f), V3(L.KILN_HOLE_X0 - 0.002f, b, z1 + o), 0.002f),
            box(PMesh(), V3(L.KILN_HOLE_X1 + 0.002f, a, z1 - 0.01f), V3(x1 + o, b, z1 + o), 0.002f),
        )
    }

    /** Fire-brick lining inside the chamber (4 mm boards on back, sides, roof), uv in brick units. */
    fun lining(): List<PMesh> {
        val t = 0.004f; val uv = 1f / 0.23f; val e = 0.0005f
        val ix0 = L.KILN_IN_X0; val ix1 = L.KILN_IN_X1; val iz0 = L.KILN_IN_Z0; val iz1 = L.KILN_IN_Z1 + W - 0.002f
        return listOf(
            box(PMesh(), V3(ix0 + t + e, 0f, iz0), V3(ix1 - t - e, L.KILN_IN_Y1 - t - e, iz0 + t), 0.001f, uv),
            box(PMesh(), V3(ix0, 0f, iz0), V3(ix0 + t, L.KILN_IN_Y1 - t - e, iz1), 0.001f, uv),
            box(PMesh(), V3(ix1 - t, 0f, iz0), V3(ix1, L.KILN_IN_Y1 - t - e, iz1), 0.001f, uv),
            box(PMesh(), V3(ix0, L.KILN_IN_Y1 - t, iz0), V3(ix1, L.KILN_IN_Y1, iz1), 0.001f, uv),
        )
    }

    /** Heating coils in grooves: left / right / back walls, two each (y 0.15 / 0.30). */
    fun elements(): PMesh {
        val m = PMesh()
        val ix0 = L.KILN_IN_X0 + 0.014f; val ix1 = L.KILN_IN_X1 - 0.014f; val iz0 = L.KILN_IN_Z0 + 0.014f; val iz1 = L.KILN_IN_Z1 - 0.01f
        for (y in listOf(0.15f, 0.30f)) {
            Proc.helix(V3(ix0, y, iz0 + 0.02f), V3(ix0, y, iz1), 0.007f, 0.014f, 0.0022f, 4, m)
            Proc.helix(V3(ix1, y, iz0 + 0.02f), V3(ix1, y, iz1), 0.007f, 0.014f, 0.0022f, 4, m)
            Proc.helix(V3(ix0 + 0.02f, y, iz0), V3(ix1 - 0.02f, y, iz0), 0.007f, 0.014f, 0.0022f, 4, m)
        }
        return m
    }

    // ---- door (hinge-local: x 0 … 0.40 to the right of the hinge, z 0 … 0.05 out of the face, y 0 … 0.45)
    fun door(): PMesh = box(PMesh(), V3(0.002f, 0f, 0.003f), V3(L.DOOR_W, L.DOOR_H, L.DOOR_T), 0.02f)
    fun doorLining(): PMesh = box(PMesh(), V3(0.03f, 0.005f, 0.0f), V3(0.37f, 0.415f, 0.003f), 0.001f, 1f / 0.23f)
    fun handle(): PMesh {
        val m = PMesh(); val x = 0.355f; val z = L.DOOR_T
        Proc.rod(V3(x, 0.17f, z), V3(x, 0.17f, z + 0.035f), 0.006f, 12, m, caps = true)
        Proc.rod(V3(x, 0.29f, z), V3(x, 0.29f, z + 0.035f), 0.006f, 12, m, caps = true)
        Proc.rod(V3(x, 0.164f, z + 0.035f), V3(x, 0.296f, z + 0.035f), 0.006f, 12, m, caps = true)
        return m
    }
    val PEEP = V3(0.20f, 0.31f, L.DOOR_T + 0.001f)
    fun peepRing(): PMesh = Proc.torus(0.019f, 0.0025f, 48, 10, 0f).let { t ->
        // torus about +y → rotate to face +z: (x, y, z) → (x, z, −y)
        PMesh().also { m -> for (i in 0 until t.vertexCount) m.v(t.pos[i * 3] + PEEP.x, -t.pos[i * 3 + 2] + PEEP.y, t.pos[i * 3 + 1] + PEEP.z, t.nrm[i * 3], -t.nrm[i * 3 + 2], t.nrm[i * 3 + 1], 0f, 0f); for (k in t.idx) m.idx += k }
    }
    /** The peephole's glowing ceramic plug: a thin closed disc (Ø 0.024) facing +z. */
    fun peepGlow(): PMesh = Proc.cylinder(0.012f, 0f, 0.002f, 32).let { c ->
        PMesh().also { m -> for (i in 0 until c.vertexCount) m.v(c.pos[i * 3] + PEEP.x, -c.pos[i * 3 + 2] + PEEP.y, c.pos[i * 3 + 1] + PEEP.z - 0.001f, c.nrm[i * 3], -c.nrm[i * 3 + 2], c.nrm[i * 3 + 1], 0f, 0f); for (k in c.idx) m.idx += k }
    }

    /** The door-gap glow: a 2 mm band just outside the door's edge, 2 mm in front of the shell face (bench coordinates). */
    fun gapGlow(): List<PMesh> {
        val g = 0.0015f; val b = 0.002f; val z = L.KILN_FRONT_Z + 0.001f; val t = 0.0015f
        val hx = L.HINGE.x; val dx1 = hx + L.DOOR_W
        return listOf(
            box(PMesh(), V3(hx - g - b, 0.001f, z), V3(hx - g, L.DOOR_H + g - 0.0002f, z + t), 0.0005f),
            box(PMesh(), V3(dx1 + g, 0.001f, z), V3(dx1 + g + b, L.DOOR_H + g - 0.0002f, z + t), 0.0005f),
            box(PMesh(), V3(hx - g - b, L.DOOR_H + g, z), V3(dx1 + g + b, L.DOOR_H + g + b, z + t), 0.0005f),
        )
    }

    // ---- controller box on the +x side, upper part (faces the work area)
    val CTRL = V3(x1 + 0.015f, 0.40f, L.KILN_C.z)
    fun controllerBox(): PMesh = box(PMesh(), V3(x1, 0.365f, L.KILN_C.z - 0.06f), V3(x1 + 0.03f, 0.435f, L.KILN_C.z + 0.06f), 0.004f)
    /** Digits plate (facing +x) and the HEAT lamp. */
    val DIGITS = V3(x1 + 0.0305f, 0.408f, L.KILN_C.z - 0.012f)
    val LAMP = V3(x1 + 0.031f, 0.382f, L.KILN_C.z + 0.035f)
    fun lamp(): PMesh = Proc.sphere(0.004f, 16, 8, LAMP)
    fun knob(): PMesh = PMesh().also { m -> Proc.rod(V3(x1 + 0.03f, 0.382f, L.KILN_C.z - 0.03f), V3(x1 + 0.042f, 0.382f, L.KILN_C.z - 0.03f), 0.008f, 16, m, caps = true) }
}
