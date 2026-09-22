package com.lyrebird.rc.edge

import android.util.Log
import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.intelligent.AutoSensingInfo
import dji.v5.manager.intelligent.AutoSensingInfoListener
import dji.v5.manager.intelligent.AutoSensingTarget
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.IntelligentModel

internal class V5AutoSensingProvider {
    interface Observer {
        fun onActiveChanged(active: Boolean)

        fun onTargets(targets: List<DetectedTargetSnapshot>)
    }

    private val manager = IntelligentFlightManager.getInstance()
    private var listenerRegistered = false
    private var observer: Observer? = null
    private val listener =
        object : AutoSensingInfoListener {
            override fun onAutoSensingInfoUpdate(info: AutoSensingInfo) {
                val targets =
                    info.targets
                        ?.map { target ->
                            val rect = target.rect
                            val centerX = rect?.x ?: 0.0
                            val centerY = rect?.y ?: 0.0
                            val halfWidth = (rect?.width ?: 0.0) / 2.0
                            val halfHeight = (rect?.height ?: 0.0) / 2.0
                            DetectedTargetSnapshot(
                                type = target.targetType?.name ?: "UNKNOWN",
                                left = centerX - halfWidth,
                                top = centerY - halfHeight,
                                right = centerX + halfWidth,
                                bottom = centerY + halfHeight,
                                confidence = null,
                            )
                        }.orEmpty()
                observer?.onTargets(targets)
            }

            override fun onTrackingTargetUpdate(target: AutoSensingTarget) = Unit

            override fun onIntelligentModelUpdate(models: MutableList<IntelligentModel>) = Unit

            override fun onRunningIntelligentModelUpdate(modelId: Int) = Unit
        }

    fun start(observer: Observer) {
        this.observer = observer
        if (!listenerRegistered) {
            manager.addAutoSensingInfoListener(listener)
            listenerRegistered = true
        }
        runCatching {
            manager.startAutoSensing(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        observer.onActiveChanged(true)
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "AutoSensing start failed: ${error.description()}")
                        removeListener()
                        observer.onActiveChanged(false)
                    }
                },
            )
        }.onFailure {
            Log.e(TAG, "AutoSensing start exception: ${it.message}", it)
            removeListener()
            observer.onActiveChanged(false)
        }
    }

    fun stop(observer: Observer) {
        observer.onTargets(emptyList())
        if (!listenerRegistered) {
            observer.onActiveChanged(false)
            return
        }
        runCatching {
            manager.stopAutoSensing(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = Unit

                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "AutoSensing stop failed: ${error.description()}")
                    }
                },
            )
        }.onFailure { Log.e(TAG, "AutoSensing stop exception: ${it.message}", it) }
        removeListener()
        observer.onActiveChanged(false)
    }

    fun dispose() {
        if (listenerRegistered) manager.removeAutoSensingInfoListener(listener)
        listenerRegistered = false
        observer = null
    }

    private fun removeListener() {
        if (!listenerRegistered) return
        runCatching { manager.removeAutoSensingInfoListener(listener) }
            .onFailure { Log.e(TAG, "AutoSensing listener removal exception: ${it.message}", it) }
        listenerRegistered = false
    }

    companion object {
        private const val TAG = "LyrebirdAutoSensing"
    }
}
