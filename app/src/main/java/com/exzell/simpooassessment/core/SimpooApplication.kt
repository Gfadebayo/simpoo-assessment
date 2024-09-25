package com.exzell.simpooassessment.core

import android.app.Activity
import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import com.exzell.simpooassessment.data.SmsManager
import com.exzell.simpooassessment.local.LocalRepository
import logcat.AndroidLogcatLogger
import logcat.logcat

class SimpooApplication: Application() {

    val localRepo by lazy { LocalRepository(this) }

    override fun onCreate() {
        super.onCreate()
        AndroidLogcatLogger.installOnDebuggableApp(this)
        localRepo

        logcat { "Let's get ready to rumble!!" }

        SmsManager.watchIncomingSms(this, localRepo)
    }
}

val AppCompatActivity.localRepo: LocalRepository
    get() = (application as SimpooApplication).localRepo