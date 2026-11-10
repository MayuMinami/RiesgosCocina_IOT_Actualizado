package com.valentina.riesgoscocina

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// Mide el consumo de batería de la app con el tema claro y con el oscuro, con brillo fijo
class EnergiaActivity : AppCompatActivity() {

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var bateria: BatteryManager
    private lateinit var tvEstado: TextView
    private lateinit var tvDetalle: TextView
    private lateinit var tvResultados: TextView
    private lateinit var btnMedir1: MaterialButton
    private lateinit var btnMedir5: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private lateinit var btnClaro: MaterialButton
    private lateinit var btnOscuro: MaterialButton

    private var midiendo = false
    private var duracionMs = 0L
    private var inicioMs = 0L
    private var cargaInicialUah = 0L
    private var nivelInicial = 0
    private var sumaMa = 0.0
    private var muestras = 0
    private var temaDeLaMedicion = ""

    private val tic = object : Runnable {
        override fun run() {
            if (!midiendo) return
            muestrear()
            val transcurrido = SystemClock.elapsedRealtime() - inicioMs
            if (transcurrido >= duracionMs) terminar() else principal.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_energia)
        val raiz = findViewById<View>(R.id.energia)
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
            return
        }
        bateria = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        tvEstado = findViewById(R.id.tvEstadoMedicion)
        tvDetalle = findViewById(R.id.tvDetalleMedicion)
        tvResultados = findViewById(R.id.tvResultados)
        btnMedir1 = findViewById(R.id.btnMedir1)
        btnMedir5 = findViewById(R.id.btnMedir5)
        btnCancelar = findViewById(R.id.btnCancelarMedicion)
        btnClaro = findViewById(R.id.btnTemaClaro)
        btnOscuro = findViewById(R.id.btnTemaOscuro)

        findViewById<TextView>(R.id.tvTemaActual).text =
            "Tema actual: ${if (Tema.esOscuro(this)) "oscuro" else "claro"}"
        btnClaro.setOnClickListener { if (!midiendo) Tema.cambiar(this, false) }
        btnOscuro.setOnClickListener { if (!midiendo) Tema.cambiar(this, true) }
        btnMedir1.setOnClickListener { iniciar(60_000L) }
        btnMedir5.setOnClickListener { iniciar(300_000L) }
        btnCancelar.setOnClickListener { cancelar("Medición cancelada") }
        findViewById<MaterialButton>(R.id.btnBorrarResultados).setOnClickListener {
            prefs().edit().remove(CLAVE_RESULTADOS).apply()
            mostrarResultados()
        }
        mostrarResultados()
    }

    private fun prefs() = getSharedPreferences("energia", Context.MODE_PRIVATE)

    private fun conectadoACarga(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun nivel(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    // Corriente instantánea en mA (el valor absoluto, porque el signo depende del fabricante)
    private fun corrienteMa(): Double {
        val bruto = bateria.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()
        if (bruto == 0.0 || bruto == Int.MIN_VALUE.toDouble()) return 0.0
        // Lo normal es microamperios; algunos Samsung antiguos devuelven miliamperios
        return if (abs(bruto) >= 20_000) abs(bruto) / 1000.0 else abs(bruto)
    }

    private fun iniciar(duracion: Long) {
        if (midiendo) return
        if (conectadoACarga()) {
            Toast.makeText(this, "Desconecta el cable USB para medir la batería", Toast.LENGTH_LONG).show()
            tvEstado.text = "Cable conectado"
            tvDetalle.text = "Con el teléfono cargando no se puede medir el consumo. Desconéctalo y vuelve a pulsar."
            return
        }
        midiendo = true
        duracionMs = duracion
        inicioMs = SystemClock.elapsedRealtime()
        cargaInicialUah = bateria.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        nivelInicial = nivel()
        sumaMa = 0.0
        muestras = 0
        temaDeLaMedicion = if (Tema.esOscuro(this)) "Oscuro" else "Claro"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.5f }
        btnMedir1.visibility = View.GONE
        btnMedir5.visibility = View.GONE
        btnClaro.isEnabled = false
        btnOscuro.isEnabled = false
        btnCancelar.visibility = View.VISIBLE
        tvEstado.text = "Midiendo con tema $temaDeLaMedicion…"
        principal.postDelayed(tic, 5_000)
    }

    private fun muestrear() {
        val ma = corrienteMa()
        if (ma > 0) {
            sumaMa += ma
            muestras++
        }
        val seg = (SystemClock.elapsedRealtime() - inicioMs) / 1000
        tvDetalle.text = "Tiempo: ${seg} s de ${duracionMs / 1000} s\n" +
            "Corriente ahora: %.0f mA\n".format(ma) +
            "Promedio: %.0f mA (%d muestras)".format(if (muestras > 0) sumaMa / muestras else 0.0, muestras)
    }

    private fun terminar() {
        val horas = (SystemClock.elapsedRealtime() - inicioMs) / 3_600_000.0
        val cargaFinal = bateria.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val consumidoMah = (cargaInicialUah - cargaFinal) / 1000.0
        val promedioMa = if (muestras > 0) sumaMa / muestras else 0.0
        val porCarga = if (cargaInicialUah > 0 && cargaFinal > 0 && horas > 0) consumidoMah / horas else 0.0
        val minutos = duracionMs / 60_000
        val fecha = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        val linea = "$fecha|$temaDeLaMedicion|$minutos|%.0f|%.0f|%.2f|$nivelInicial|${nivel()}".format(
            promedioMa, porCarga, consumidoMah
        )
        val previo = prefs().getString(CLAVE_RESULTADOS, "") ?: ""
        prefs().edit().putString(CLAVE_RESULTADOS, if (previo.isEmpty()) linea else "$previo\n$linea").apply()
        finalizarUi("Medición terminada")
    }

    private fun cancelar(texto: String) {
        if (!midiendo) return
        finalizarUi(texto)
    }

    private fun finalizarUi(texto: String) {
        midiendo = false
        principal.removeCallbacks(tic)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        btnMedir1.visibility = View.VISIBLE
        btnMedir5.visibility = View.VISIBLE
        btnClaro.isEnabled = true
        btnOscuro.isEnabled = true
        btnCancelar.visibility = View.GONE
        tvEstado.text = texto
        tvDetalle.text = getString(R.string.energia_aviso)
        mostrarResultados()
    }

    private fun mostrarResultados() {
        val datos = prefs().getString(CLAVE_RESULTADOS, "") ?: ""
        if (datos.isEmpty()) {
            tvResultados.text = getString(R.string.energia_sin_resultados)
            return
        }
        // fecha | tema | min | mA (sensor) | mA (contador de carga) | mAh gastados | % inicial | % final
        tvResultados.text = datos.lines().reversed().joinToString("\n\n") { l ->
            val p = l.split("|")
            if (p.size < 8) l else
                "${p[0]}  Tema ${p[1]}  ${p[2]} min\n" +
                    "  Corriente media: ${p[3]} mA\n" +
                    "  Por contador de carga: ${p[4]} mA (${p[5]} mAh)\n" +
                    "  Batería: ${p[6]} % → ${p[7]} %"
        }
    }

    override fun onDestroy() {
        principal.removeCallbacks(tic)
        super.onDestroy()
    }

    companion object {
        private const val CLAVE_RESULTADOS = "resultados"
    }
}
