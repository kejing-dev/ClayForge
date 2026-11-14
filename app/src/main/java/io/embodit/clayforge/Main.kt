package io.embodit.clayforge

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.clayforge.ui.PanelWindow
import io.embodit.clayforge.ui.StudioVolume

/** Id of the default volumetric container (AndroidManifest `pico.spatial.windowcontainer.id`). */
const val STUDIO_ID = "Studio"
const val PANEL_ID = "Panel"
const val PANEL_TAG = "panel"
const val PANEL_W_DP = 520
const val PANEL_H_DP = 460

/**
 * App entry (design `container`: IN_VOLUME, window model `volume_plus_system_panel`, archetype A): the default container
 * is the Shared Space volume (clay, turntable, tool tray, kiln); the panel is a second, planar system window that the
 * system places right of the volume (`Placement.right`, 48 dp). Moving / closing both is the system's (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { StudioVolume() }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PANEL_W_DP.dp, height = PANEL_H_DP.dp),
            placement = { containers.firstOrNull { it.id == STUDIO_ID }?.let { Placement.right(it, 48.dp) } ?: Placement.none() },
        ) { PanelWindow() }
    }
