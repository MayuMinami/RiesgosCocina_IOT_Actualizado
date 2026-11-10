package com.valentina.riesgoscocina

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

// Tema de la app: oscuro por defecto (ahorra batería en pantallas OLED); el usuario puede cambiarlo
object Tema {
    private const val PREFS = "ajustes"
    private const val CLAVE = "tema_oscuro"

    fun esOscuro(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE, true)
    } catch (_: Exception) { true }

    fun aplicar(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (esOscuro(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun cambiar(context: Context, oscuro: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLAVE, oscuro).apply()
        } catch (_: Exception) { }
        aplicar(context)
    }
}
