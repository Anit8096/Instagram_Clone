package com.android.insta

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.android.insta.di.APP_SCOPE
import com.android.insta.di.appModules
import com.android.insta.feature.chat.data.RealtimeClient
import com.android.insta.feature.notifications.data.ActivityBadge
import com.android.insta.feature.notifications.data.PushRegistrar
import com.android.insta.feature.notifications.push.SystemNotifier
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

class InstaApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.ERROR)
            androidContext(this@InstaApp)
            // Workers get constructor injection; the default WorkManager initializer is disabled in the manifest.
            workManagerFactory()
            modules(appModules)
        }
        // Realtime socket: open while signed in and in the foreground.
        get<RealtimeClient>().bind(get(APP_SCOPE), get())
        // Activity badge (socket-driven) and FCM token registration follow the session.
        get<ActivityBadge>().bind(get(APP_SCOPE), get())
        get<PushRegistrar>().bind(get(APP_SCOPE), get())
        get<SystemNotifier>().createChannels()
    }

    /** Coil's AsyncImage uses the Koin-provided loader (shared HttpClient + disk cache). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = get()
}
