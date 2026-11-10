package com.valentina.riesgoscocina

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.valentina.riesgoscocina.iot.IotManager
import com.valentina.riesgoscocina.iot.SyncManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HistorialActivity : PantallaIot() {

    private val hilo = Executors.newSingleThreadExecutor()
    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    private lateinit var tvConexion: TextView
    private lateinit var tvTotales: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvLecturas: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_historial)
        tvConexion = findViewById(R.id.tvConexion)
        tvTotales = findViewById(R.id.tvTotales)
        tvSync = findViewById(R.id.tvSync)
        tvLecturas = findViewById(R.id.tvLecturas)

        findViewById<MaterialButton>(R.id.btnSincronizar).setOnClickListener {
            tvSync.text = "Sincronizando con DynamoDB…"
            SyncManager.sincronizar(this) { runOnUiThread { refrescar() } }
        }
        findViewById<MaterialButton>(R.id.btnRecuperar).setOnClickListener {
            tvSync.text = "Leyendo lecturas desde DynamoDB…"
            SyncManager.recuperar(this) { runOnUiThread { refrescar() } }
        }
        findViewById<MaterialButton>(R.id.btnBorrarLocal).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar copia local")
                .setMessage("Se borrarán del teléfono solo las lecturas que ya están en DynamoDB. Las pendientes no se tocan y podrás recuperarlas con \"Recuperar desde la nube\".")
                .setPositiveButton("Borrar") { _, _ ->
                    hilo.execute {
                        val n = IotManager.base(applicationContext).borrarSincronizadas()
                        runOnUiThread {
                            tvSync.text = "Se borraron $n lecturas locales (siguen en DynamoDB)"
                            refrescar()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        prepararPantalla(R.id.historial)
    }

    // Muestra las lecturas guardadas en el teléfono y su estado en DynamoDB
    override fun refrescar() {
        mostrarConexion(tvConexion)
        tvSync.text = SyncManager.ultimoResultado
        val app = applicationContext
        hilo.execute {
            val db = IotManager.base(app)
            val total = db.contar(false)
            val pendientes = db.contar(true)
            val lecturas = db.recientes(50)
            val texto = if (lecturas.isEmpty()) getString(R.string.sin_lecturas)
            else lecturas.joinToString("\n") { l ->
                val temp = l.temp?.let { "%.1f°C".format(it) } ?: "--"
                val hum = l.hum?.let { "%.0f%%".format(it) } ?: "--"
                "${if (l.sincronizada) "[AWS]" else "[local]"} #${l.id}  ${hora.format(Date(l.recibido))}\n" +
                    "   $temp  $hum  gas ${l.gas}${if (l.alarma) "  ALERTA" else ""}"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                tvTotales.text = "Guardadas: $total · En DynamoDB: ${total - pendientes} · Pendientes: $pendientes"
                tvLecturas.text = texto
            }
        }
    }

    override fun onDestroy() {
        hilo.shutdown()
        super.onDestroy()
    }
}
