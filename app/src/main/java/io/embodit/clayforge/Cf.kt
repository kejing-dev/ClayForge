package io.embodit.clayforge

import android.content.Context
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.clayforge.platform.WindowCtl
import io.embodit.clayforge.scene.ClayScene
import java.lang.ref.WeakReference

/** Process-level services shared by the Stage and the control window. */
object Cf {
    lateinit var appContext: Context
    var scene: WeakReference<ClayScene>? = null
    /** The Stage's navigator (alive as long as the app): window open / close / exit run through it. */
    var navigator: SpatialNavigator? = null
    /** Opens / reopens the control window from the Stage through the level gate. */
    val window = WindowCtl()
    /** Closes the Stage (set by the Stage screen; exit closes both containers, D44). */
    var closeStage: (suspend () -> Unit)? = null
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        Studio.init(appContext)
    }
}
