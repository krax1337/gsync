package io.github.krax1337.gsync

import android.app.Application
import io.github.krax1337.gsync.watch.WatchManager

class GsyncApp : Application() {
    lateinit var manager: WatchManager
        private set

    override fun onCreate() {
        super.onCreate()
        manager = WatchManager(this)
    }
}
