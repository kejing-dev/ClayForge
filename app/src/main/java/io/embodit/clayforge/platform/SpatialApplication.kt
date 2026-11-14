package io.embodit.clayforge.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.clayforge.Cf
import io.embodit.clayforge.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CfLog.attach(this)
        Cf.init(this)
        launch(::mainApp)
    }
}
