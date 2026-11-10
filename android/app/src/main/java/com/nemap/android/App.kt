package com.nemap.android

import android.app.Application
import com.nemap.android.core.AppState
import com.nemap.android.core.NemLog
import com.nemap.android.web.Engine

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(assets)
        NemLog.i("service", "NemApi Android ${AppState.VERSION} démarré")
    }
}
