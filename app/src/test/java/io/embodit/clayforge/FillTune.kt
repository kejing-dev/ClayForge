package io.embodit.clayforge

import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.FillShape
import io.embodit.clayforge.domain.SurfaceNets
import org.junit.Assert.assertTrue
import org.junit.Test

class FillTune {
    @Test fun fillLandsJustOverTheBudget() {
        val f = ClayField().apply { fromSdf(FillShape::sdf) }
        val t = (0 until ClayField.CHUNKS).sumOf { SurfaceNets().mesh(f, it).triangles }
        println("fill r=${FillShape.radius} p=${FillShape.period} tris=$t")
        assertTrue("$t", t in 55_000..64_000)
    }
}
