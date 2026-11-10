package com.valentina.riesgoscocina

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.valentina.riesgoscocina.iot.IotManager
import com.valentina.riesgoscocina.iot.Lectura
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MonitorActivity : PantallaIot() {

    private val hilo = Executors.newSingleThreadExecutor()
    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    private lateinit var tvConexion: TextView
    private lateinit var cardAlerta: MaterialCardView
    private lateinit var tvAlerta: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvHum: TextView
    private lateinit var tvGas: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvActualizado: TextView
    private lateinit var tvAlertas: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_monitor)
        tvConexion = findViewById(R.id.tvConexion)
        cardAlerta = findViewById(R.id.cardAlerta)
        tvAlerta = findViewById(R.id.tvAlerta)
        tvTemp = findViewById(R.id.tvTemp)
        tvHum = findViewById(R.id.tvHum)
        tvGas = findViewById(R.id.tvGas)
        tvEstado = findViewById(R.id.tvEstado)
        tvActualizado = findViewById(R.id.tvActualizado)
        tvAlertas = findViewById(R.id.tvAlertas)
        prepararPantalla(R.id.monitor)
    }

    // Actualiza los sensores y las alertas con cada mensaje del ESP32
    override fun refrescar() {
        mostrarConexion(tvConexion)
        val l = IotManager.ultimaLectura
        tvTemp.text = l?.temp?.let { "%.1f °C".format(it) } ?: getString(R.string.sin_dato)
        tvHum.text = l?.hum?.let { "%.0f %%".format(it) } ?: getString(R.string.sin_dato)
        tvGas.text = l?.gas?.toString() ?: getString(R.string.sin_dato)
        val rojo = ContextCompat.getColor(this, R.color.rojo_alerta)
        val normal = ContextCompat.getColor(this, R.color.borgona)
        val temp = l?.temp
        val tempRiesgo = l != null && (temp == null || temp >= Lectura.TEMP_LIMITE)
        tvTemp.setTextColor(if (tempRiesgo) rojo else normal)
        tvGas.setTextColor(if ((l?.gas ?: 0) >= Lectura.GAS_LIMITE) rojo else normal)

        if (IotManager.alarma) {
            cardAlerta.visibility = View.VISIBLE
            val causas = l?.causas()?.joinToString("\n") ?: "Alarma activa"
            tvAlerta.text = "¡ALERTA EN LA COCINA!\n$causas"
        } else cardAlerta.visibility = View.GONE

        val armado = when (IotManager.armado) {
            true -> "Sistema armado"
            false -> "Sistema desarmado"
            null -> "Estado del sistema desconocido"
        }
        tvEstado.text = "$armado · ${if (IotManager.alarma) "alarma activa" else "sin alarma"}" +
            (if (IotManager.fallaSensor) " · falla del sensor DHT" else "") +
            (IotManager.enCola?.let { " · en cola $it" } ?: "")
        tvActualizado.text = (l?.let { "Última lectura #${it.id} a las ${hora.format(Date(it.recibido))}" }
            ?: "Esperando lecturas del ESP32…") +
            (if (IotManager.conexion != IotManager.Conexion.CONECTADO || IotManager.dispositivo == null)
                "\n⚠ Modo contingencia: sin enlace con el ESP32. Se muestra la última lectura guardada; la app reintenta " +
                    "cada 10 s y el ESP32 conserva hasta 120 lecturas en su cola para enviarlas al volver."
            else "") +
            (if (IotManager.rechazados > 0) "\nMensajes rechazados por firma HMAC: ${IotManager.rechazados}" else "")

        val app = applicationContext
        hilo.execute {
            val alertas = IotManager.base(app).alertas(10)
            val texto = if (alertas.isEmpty()) getString(R.string.sin_alertas)
            else alertas.joinToString("\n\n") { "${hora.format(Date(it.recibido))}\n${it.causas().joinToString(", ")}" }
            runOnUiThread { if (!isFinishing) tvAlertas.text = texto }
        }
    }

    override fun onDestroy() {
        hilo.shutdown()
        super.onDestroy()
    }
}
