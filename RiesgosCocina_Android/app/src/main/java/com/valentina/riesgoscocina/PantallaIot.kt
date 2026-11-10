package com.valentina.riesgoscocina

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.valentina.riesgoscocina.iot.IotManager

// Base de las pantallas del menú: márgenes del sistema y refresco en tiempo real
abstract class PantallaIot : AppCompatActivity() {

    private val oyente: () -> Unit = { refrescar() }
    private var lista = false

    protected fun prepararPantalla(raizId: Int): Boolean {
        val raiz = findViewById<View>(raizId)
        val pad = raiz.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + barras.left, pad + barras.top, pad + barras.right, pad + barras.bottom)
            insets
        }
        if (Sesion.correo.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return false
        }
        IotManager.conectar(this)
        lista = true
        return true
    }

    override fun onStart() {
        super.onStart()
        if (lista) {
            IotManager.agregarOyente(oyente)
            refrescar()
        }
    }

    override fun onStop() {
        IotManager.quitarOyente(oyente)
        super.onStop()
    }

    protected abstract fun refrescar()

    // Muestra el estado de la conexión MQTT y del ESP32
    protected fun mostrarConexion(tv: TextView) {
        val enLinea = IotManager.conexion == IotManager.Conexion.CONECTADO && IotManager.dispositivo != null
        tv.text = when {
            IotManager.conexion != IotManager.Conexion.CONECTADO -> "● Conectando al broker MQTT…"
            IotManager.dispositivo == null -> "● Broker conectado (TLS) · buscando ESP32…"
            else -> "● ESP32 en línea · MQTT con TLS"
        }
        tv.setTextColor(ContextCompat.getColor(this, if (enLinea) R.color.verde_ok else R.color.borgona_suave))
    }
}
