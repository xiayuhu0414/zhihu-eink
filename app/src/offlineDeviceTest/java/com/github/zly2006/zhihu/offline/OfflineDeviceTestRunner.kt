package com.github.zly2006.zhihu.offline

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Isolated data/transport tests do not start application background requests or any UI. */
class OfflineDeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
