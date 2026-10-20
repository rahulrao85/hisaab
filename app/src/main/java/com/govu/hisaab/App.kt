package com.govu.hisaab

import android.app.Application
import com.govu.hisaab.work.Jobs
import com.govu.hisaab.work.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        Jobs.scheduleMorning(this)
    }
}
