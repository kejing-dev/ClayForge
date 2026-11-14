package io.embodit.clayforge.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.clayforge.Cf
import io.embodit.clayforge.PANEL_ID
import io.embodit.clayforge.PANEL_TAG
import io.embodit.clayforge.Page
import io.embodit.clayforge.R
import io.embodit.clayforge.Studio
import io.embodit.clayforge.platform.CfLog
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

private val TAB_LABEL = mapOf(Page.LEARN to R.string.tab_learn, Page.FREE to R.string.tab_free, Page.WORKS to R.string.tab_works, Page.SETTINGS to R.string.tab_settings)
private val TAB_ICON = mapOf(Page.LEARN to R.drawable.ic_school, Page.FREE to R.drawable.ic_pan_tool, Page.WORKS to R.drawable.ic_shelves, Page.SETTINGS to R.drawable.ic_settings)

/** The control window: one planar system window (MainWindow template) with the native TabBar (课 / 自由捏 / 作品 / 设置, D3). */
@Composable
fun PanelWindow() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> Cf.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val res = localizedResources(context, Studio.prefs.lang)
    CompositionLocalProvider(LocalCfResources provides res) {
        PicoTheme {
            ContrastLog()
            val page = Studio.page
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp)) {
                    when (page) {
                        Page.LEARN -> LearnPage()
                        Page.FREE -> SculptPage()
                        Page.WORKS -> WorksPage()
                        Page.SETTINGS -> SettingsPage()
                    }
                }
            }
            TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
                Page.entries.forEach { p ->
                    val label = res.getString(TAB_LABEL.getValue(p))
                    item(
                        selected = page == p,
                        onClick = { Studio.openPage(p, "tab") },
                        mainContent = { Icon(painter = painterResource(TAB_ICON.getValue(p)), contentDescription = label) },
                        supportContent = { Text(label) },
                    )
                }
            }
        }
    }
}

/**
 * D26 / D44 exit: save, release sound and trackers, close BOTH containers (control window + Stage), remove every task,
 * end the process (五子棋 / 九路围棋: closing only the window left a recents entry).
 */
suspend fun exitApp(context: Context, navigator: SpatialNavigator) {
    CfLog.i("EXIT save + release + close panel + close stage + finishAndRemoveTask + exitProcess")
    Cf.window.exiting = true
    Studio.saveNow()
    runCatching { Studio.sound.release() }
    runCatching { Cf.scene?.get()?.release() }
    runCatching { (Cf.navigator ?: navigator).closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { CfLog.w("EXIT close panel: $it") }
    delay(150)
    runCatching { Cf.closeStage?.invoke() }.onFailure { CfLog.w("EXIT close stage: $it") }
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    repeat(2) { round ->
        delay(300)
        runCatching {
            val tasks = am.appTasks
            CfLog.i("EXIT round=$round tasks=${tasks.map { t -> runCatching { t.taskInfo.taskId }.getOrDefault(-1) }}")
            tasks.forEach { t -> runCatching { t.finishAndRemoveTask() } }
        }.onFailure { CfLog.e("EXIT finishAndRemoveTask failed", it) }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

/**
 * D13: PicoTheme label / fill roles are "vibrant" colours the system composites over the glass (their RGB reads as pure
 * black), so panel text contrast is measured on screenshots (ui-audit §对比度). The one pair the app sets itself is logged.
 */
@Composable
private fun ContrastLog() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        fun lum(col: androidx.compose.ui.graphics.Color): Double {
            fun ch(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(col.red) + 0.7152 * ch(col.green) + 0.0722 * ch(col.blue)
        }
        CfLog.i("CONTRAST white on PrimaryBlue ${"%.2f".format(1.05 / (lum(PrimaryBlue) + 0.05))}")
    }
}
