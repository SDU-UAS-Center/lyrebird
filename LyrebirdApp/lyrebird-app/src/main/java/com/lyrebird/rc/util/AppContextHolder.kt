package com.lyrebird.rc.util

import android.content.Context

/**
 * The process application context, installed once by the flavor's Application class.
 *
 * Shared helpers that run outside any Activity (settings profiles, backups, fleet profile
 * storage) resolve durable storage through it instead of an SDK's own context utility — the SDK
 * utility only exists in one flavor, which would otherwise drag those helpers into that flavor.
 * Null before installation: the storage helpers degrade to "no durable directory", the same path
 * they take when the operator has not granted full storage access.
 */
internal object AppContextHolder {
    @Volatile
    private var appContext: Context? = null

    val context: Context? get() = appContext

    fun install(context: Context) {
        appContext = context.applicationContext
    }
}
