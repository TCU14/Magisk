package com.topjohnwu.magisk

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.AsyncTask
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDex
import androidx.room.Room
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkDatabase_Impl
import com.topjohnwu.magisk.data.database.RepoDatabase
import com.topjohnwu.magisk.data.database.RepoDatabase_Impl
import com.topjohnwu.magisk.di.ActivityTracker
import com.topjohnwu.magisk.di.koinModules
import com.topjohnwu.magisk.extensions.get
import com.topjohnwu.magisk.utils.LocaleManager
import com.topjohnwu.magisk.utils.RootUtils
import com.topjohnwu.net.Networking
import com.topjohnwu.superuser.Shell
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber
import java.util.concurrent.ThreadPoolExecutor

open class App : Application() {

    lateinit var deContext: Context

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (BuildConfig.DEBUG)
            MultiDex.install(base)
        Timber.plant(Timber.DebugTree())

        startKoin {
            androidContext(this@App)
            modules(koinModules)
        }

        deContext = base
        self = this

        if (Build.VERSION.SDK_INT >= 24) {
            deContext = base.createDeviceProtectedStorageContext()
            deContext.moveSharedPreferencesFrom(base, defaultPrefsName)
        }

        registerActivityLifecycleCallbacks(get<ActivityTracker>())

        Networking.init(base)
        LocaleManager.setLocale(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleManager.setLocale(this)
    }

    private val Context.defaultPrefsName get() = "${packageName}_preferences"

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Deprecated("Use dependency injection")
        @JvmStatic
        lateinit var self: App

        @Deprecated("Use Rx or similar")
        @JvmField
        var THREAD_POOL: ThreadPoolExecutor

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
            Shell.Config.setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_USE_MAGISK_BUSYBOX)
            Shell.Config.verboseLogging(BuildConfig.DEBUG)
            Shell.Config.addInitializers(RootUtils::class.java)
            Shell.Config.setTimeout(2)
            THREAD_POOL = AsyncTask.THREAD_POOL_EXECUTOR as ThreadPoolExecutor
            Room.setFactory {
                when (it) {
                    WorkDatabase::class.java -> WorkDatabase_Impl()
                    RepoDatabase::class.java -> RepoDatabase_Impl()
                    else -> null
                }
            }
        }
    }
}
