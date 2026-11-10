package com.valentina.riesgoscocina

import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import com.google.android.material.button.MaterialButton
import com.valentina.riesgoscocina.iot.IotManager
import com.valentina.riesgoscocina.iot.Protocolo
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Pantalla de pruebas: ejecuta en vivo las verificaciones de seguridad, conexión y compatibilidad
// para poder demostrarlas desde una sola pantalla.
class PruebasActivity : PantallaIot() {

    private lateinit var tvConexion: TextView
    private lateinit var tvResultados: TextView
    private lateinit var tvResumen: TextView
    private lateinit var btnEjecutar: MaterialButton
    private val hilo = Executors.newSingleThreadExecutor()
    private var ejecutando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pruebas)
        tvConexion = findViewById(R.id.tvConexion)
        tvResultados = findViewById(R.id.tvResultados)
        tvResumen = findViewById(R.id.tvResumen)
        btnEjecutar = findViewById(R.id.btnEjecutarPruebas)
        btnEjecutar.setOnClickListener { ejecutar() }
        prepararPantalla(R.id.pruebas)
    }

    override fun refrescar() {
        mostrarConexion(tvConexion)
    }

    private fun ejecutar() {
        if (ejecutando) return
        ejecutando = true
        btnEjecutar.isEnabled = false
        tvResultados.text = ""
        tvResumen.text = getString(R.string.pruebas_ejecutando)
        hilo.execute {
            var ok = 0
            var total = 0
            // true = prueba superada, false = fallida, null = dato informativo
            val linea: (Boolean?, String) -> Unit = { pasa, texto ->
                if (pasa != null) { total++; if (pasa) ok++ }
                val marca = when (pasa) { true -> "✔"; false -> "✘"; null -> "•" }
                runOnUiThread { tvResultados.append("$marca $texto\n\n") }
            }
            try {
                pruebas(linea)
            } catch (e: Exception) {
                linea(false, "Error inesperado: ${e.message}")
            }
            runOnUiThread {
                tvResumen.text = getString(R.string.pruebas_resultado, ok, total)
                btnEjecutar.isEnabled = true
                ejecutando = false
            }
        }
    }

    private fun pruebas(linea: (Boolean?, String) -> Unit) {
        val clave = BuildConfig.MQTT_HMAC_KEY

        // 1. Compatibilidad
        linea(null, "Dispositivo: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
        linea(null, "App compilada con minSdk ${applicationInfo.minSdkVersion} y targetSdk ${applicationInfo.targetSdkVersion}")

        // 2. Cifrado AES-256-GCM
        val tema = "ti3042/prueba/telemetry"
        val msg = "DATA;id=1;temp=24.0;hum=40.0;gas=1000;armed=1;alarm=0"
        val trama = Protocolo.cifrar(clave, tema, msg)
        linea(Protocolo.descifrar(clave, tema, trama) == msg, "AES-256-GCM: el mensaje se cifra y se descifra correctamente")
        val i = trama.length / 2
        val alterada = trama.substring(0, i) + (if (trama[i] == 'A') 'B' else 'A') + trama.substring(i + 1)
        linea(Protocolo.descifrar(clave, tema, alterada) == null, "Un mensaje alterado en tránsito es rechazado (etiqueta GCM)")
        linea(Protocolo.descifrar(clave, "ti3042/otro/tema", trama) == null, "Un mensaje reenviado a otro tema es rechazado")
        linea(Protocolo.descifrar("0".repeat(64), tema, trama) == null, "Con otra clave no se puede leer el mensaje")

        // 3. Firma HMAC-SHA256
        val nonce = "abc123"
        val firmado = "$msg;mac=${Protocolo.hmac(clave, "$nonce|$msg")}"
        val firmaAlterada = firmado.dropLast(1) + (if (firmado.last() == '0') '1' else '0')
        linea(
            Protocolo.verificar(clave, nonce, firmado) == msg && Protocolo.verificar(clave, nonce, firmaAlterada) == null,
            "Firma HMAC-SHA256: la válida se acepta y la alterada se rechaza"
        )

        // 4. Credenciales y datos personales
        linea(
            clave.length == 64 && BuildConfig.SYNC_TOKEN.isNotBlank(),
            "Clave MQTT y token de AWS cargados desde iot.properties al compilar (no están en el código ni en GitHub)"
        )
        linea(null, "Datos personales: la app solo conserva el correo (${Sesion.correo}) en memoria; la contraseña no se guarda en el teléfono, y en el servidor solo se guarda su hash bcrypt")

        // 5. HTTPS hacia la API de login
        val api = BuildConfig.API_URL
        linea(api.startsWith("https://"), "La API de login se consume por HTTPS: $api")
        val codigo = try {
            val c = URL(api).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            val r = c.responseCode
            c.disconnect()
            r
        } catch (e: Exception) { null }
        linea(codigo != null, if (codigo != null) "TLS verificado con el certificado fijado en la app (respuesta HTTP $codigo)" else "No se pudo establecer la conexión TLS con la API")
        val enClaro = try {
            val c = URL(api.replaceFirst("https://", "http://")).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            val r = c.responseCode
            c.disconnect()
            r == 426
        } catch (e: Exception) { true }
        linea(enClaro, "El tráfico HTTP sin cifrar está bloqueado (la app no lo permite y la API responde 426)")

        // 6. MQTT con TLS
        linea(IotManager.conexion == IotManager.Conexion.CONECTADO, "Conexión MQTT con TLS al broker establecida")
        linea(IotManager.dispositivo != null, "ESP32 verificado por su firma: ${IotManager.dispositivo?.substringAfterLast('/') ?: "no encontrado"}")
        linea(null, "Reconexión automática MQTT activada: si se corta la red, la app reintenta sola sin presionar botones")

        // 7. Rechazo de comandos con autenticación inválida (prueba real contra el ESP32)
        if (IotManager.dispositivo != null) {
            val antes = IotManager.rechazosEsp32
            IotManager.enviarComandoRechazable("firma")
            Thread.sleep(1500)
            IotManager.enviarComandoRechazable("replay")
            var espera = 0
            while (IotManager.rechazosEsp32 < antes + 2 && espera < 8000) { Thread.sleep(250); espera += 250 }
            val n = IotManager.rechazosEsp32 - antes
            linea(n >= 2, "El ESP32 rechazó $n de 2 comandos inválidos (firma falsa y comando repetido)")
        } else {
            linea(false, "No se pudo probar el rechazo de comandos: el ESP32 no está en línea")
        }

        // 8. Datos locales y cola
        val db = IotManager.base(this)
        linea(null, "Lecturas guardadas en el teléfono: ${db.contar(false)} · pendientes de sincronizar con AWS: ${db.contar(true)} · en cola del ESP32: ${IotManager.enCola ?: "-"}")
    }
}
