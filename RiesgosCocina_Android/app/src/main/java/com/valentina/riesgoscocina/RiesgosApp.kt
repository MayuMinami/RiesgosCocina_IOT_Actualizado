package com.valentina.riesgoscocina

import android.app.Application

class RiesgosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tema.aplicar(this)
    }
}
