package com.valentina.riesgoscocina

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import com.google.android.material.button.MaterialButton
import com.valentina.riesgoscocina.iot.IotManager

class ControlActivity : PantallaIot() {

    private lateinit var tvConexion: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvEventos: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_control)
        tvConexion = findViewById(R.id.tvConexion)
        tvEstado = findViewById(R.id.tvEstado)
        tvEventos = findViewById(R.id.tvEventos)

        mapOf(
            R.id.btnArmar to "ARM",
            R.id.btnDesarmar to "DISARM",
            R.id.btnSilenciar to "SILENCE",
            R.id.btnRestablecer to "RESETALARM",
            R.id.btnEstado to "STATUS"
        ).forEach { (id, op) ->
            findViewById<MaterialButton>(id).setOnClickListener { enviar(op) }
        }
        findViewById<MaterialButton>(R.id.btnPruebaFirma).setOnClickListener { probarRechazo("firma") }
        findViewById<MaterialButton>(R.id.btnPruebaReplay).setOnClickListener { probarRechazo("replay") }
        prepararPantalla(R.id.control)
    }

    // Envía un comando firmado con HMAC al ESP32
    private fun enviar(op: String) {
        val texto = if (IotManager.enviarComando(op))
            getString(R.string.comando_enviado, IotManager.nombreComando(op))
        else getString(R.string.comando_sin_conexion)
        Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
    }

    // Demuestra que el ESP32 rechaza comandos con autenticación inválida
    private fun probarRechazo(tipo: String) {
        val ok = IotManager.enviarComandoRechazable(tipo)
        Toast.makeText(this, if (ok) getString(R.string.prueba_enviada) else getString(R.string.comando_sin_conexion),
            Toast.LENGTH_SHORT).show()
    }

    override fun refrescar() {
        mostrarConexion(tvConexion)
        val armado = when (IotManager.armado) {
            true -> "Armado"
            false -> "Desarmado"
            null -> "Desconocido"
        }
        tvEstado.text = "Sistema: $armado\nAlarma: ${if (IotManager.alarma) "ACTIVA" else "inactiva"}" +
            "\nLecturas en cola del ESP32: ${IotManager.enCola ?: "-"}"
        val eventos = synchronized(IotManager.eventos) { IotManager.eventos.toList() }
        tvEventos.text = if (eventos.isEmpty()) getString(R.string.sin_eventos) else eventos.joinToString("\n")
    }
}
