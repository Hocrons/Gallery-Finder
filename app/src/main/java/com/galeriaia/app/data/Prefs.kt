package com.galeriaia.app.data

import android.content.Context

/** Configurações simples do app. */
object Prefs {
    private const val FILE = "galeria_prefs"
    private const val FAST_MODE = "fast_mode"

    /** Modo rápido: mais núcleos e sem pausa por temperatura (o celular esquenta mais). */
    fun fastMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(FAST_MODE, false)

    fun setFastMode(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(FAST_MODE, on).apply()
    }
}
