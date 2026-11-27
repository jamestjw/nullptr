package com.nullptr.trackpad.reactnative

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class TrackpadPackage : ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext) =
        listOf(TrackpadModule(context))

    override fun createViewManagers(context: ReactApplicationContext) =
        emptyList<ViewManager<*, *>>()
}
