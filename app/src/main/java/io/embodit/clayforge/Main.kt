package io.embodit.clayforge

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.clayforge.ui.PanelWindow
import io.embodit.clayforge.ui.StageScreen

const val PANEL_ID = "Panel"
const val PANEL_TAG = "panel"
const val PANEL_W_DP = 520
const val PANEL_H_DP = 460

/**
 * App entry (design `container`: STAGE_MIXED, window model stage_plus_system_panel): one Mixed Stage (Full Space,
 * passthrough) holds the bench, turntable, clay, tray and kiln; the control window is one Planar WindowContainer with
 * the native TabBar, opened from the Stage through the level gate. Moving / closing it is the system's (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { StageScreen() }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PANEL_W_DP.dp, height = PANEL_H_DP.dp),
        ) { PanelWindow() }
    }
