package io.mszymanski.orknux.mobile

import android.app.Application
import io.mszymanski.orknux.mobile.core.AppSession

class OrknuxApp : Application() {
    val session: AppSession by lazy { AppSession(this) }
}
