package io.embodit.clayforge

import android.content.Context
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.clayforge.scene.ClayScene
import java.lang.ref.WeakReference

/** Process-level services shared by the volume and the panel window. */
object Cf {
    lateinit var appContext: Context
    var scene: WeakReference<ClayScene>? = null
    /** The volume's navigator (alive as long as the app): exit / reopen run through it. */
    var navigator: SpatialNavigator? = null
    var onChips: () -> Unit = {}
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        Studio.init(appContext)
    }
}
