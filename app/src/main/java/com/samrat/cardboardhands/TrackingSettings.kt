package com.samrat.cardboardhands

import android.content.Context
import android.content.SharedPreferences

object TrackingSettings {
    private const val PREFS = "lostxr_tracking_prefs"

    // Режимы отображения рук
    const val HANDS_BOTH = 0
    const val HANDS_RIGHT_ONLY = 1
    const val HANDS_LEFT_ONLY = 2
    const val HANDS_DISABLED = 3

    // Аппаратный ускоритель обработки (MediaPipe Delegate)
    const val DELEGATE_GPU = 0
    const val DELEGATE_CPU = 1

    private const val KEY_HAND_RENDER_MODE = "pref_hand_render_mode"
    private const val KEY_DELEGATE = "pref_delegate"
    private const val KEY_MIN_CUTOFF = "pref_min_cutoff"
    private const val KEY_BETA = "pref_beta"
    private const val KEY_CONFIDENCE = "pref_confidence"
    private const val KEY_SMOOTHING = "pref_smoothing"

    private fun p(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Режим рук
    fun getHandRenderMode(context: Context): Int = p(context).getInt(KEY_HAND_RENDER_MODE, HANDS_BOTH)
    fun setHandRenderMode(context: Context, mode: Int) = p(context).edit().putInt(KEY_HAND_RENDER_MODE, mode).apply()

    // Ускоритель (CPU / GPU)
    fun getDelegate(context: Context): Int = p(context).getInt(KEY_DELEGATE, DELEGATE_GPU)
    fun setDelegate(context: Context, delegate: Int) = p(context).edit().putInt(KEY_DELEGATE, delegate).apply()

    // Сглаживание и фильтр 1€
    fun isSmoothingEnabled(context: Context): Boolean = p(context).getBoolean(KEY_SMOOTHING, true)
    fun setSmoothingEnabled(context: Context, v: Boolean) = p(context).edit().putBoolean(KEY_SMOOTHING, v).apply()

    fun getMinCutoff(context: Context): Float = p(context).getFloat(KEY_MIN_CUTOFF, 1.0f)
    fun setMinCutoff(context: Context, v: Float) = p(context).edit().putFloat(KEY_MIN_CUTOFF, v).apply()

    fun getBeta(context: Context): Float = p(context).getFloat(KEY_BETA, 0.007f)
    fun setBeta(context: Context, v: Float) = p(context).edit().putFloat(KEY_BETA, v).apply()

    fun getConfidence(context: Context): Float = p(context).getFloat(KEY_CONFIDENCE, 0.65f)
    fun setConfidence(context: Context, v: Float) = p(context).edit().putFloat(KEY_CONFIDENCE, v).apply()
}
