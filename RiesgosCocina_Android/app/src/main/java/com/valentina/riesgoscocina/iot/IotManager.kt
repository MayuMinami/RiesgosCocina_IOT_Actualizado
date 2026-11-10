package com.valentina.riesgoscocina.iot

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.valentina.riesgoscocina.BuildConfig
import com.valentina.riesgoscocina.MonitorActivity
import com.valentina.riesgoscocina.R
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

// Conexión MQTT sobre TLS con el ESP32 y estado en tiempo real
object IotManager {

    enum class Conexion { DESCONECTADO, CONECTANDO, CONECTADO }

    var conexion = Conexion.DESCONECTADO
        private set
    var dispositivo: String? = null
        private set
    var ultimaLectura: Lectura? = null
        private set
    var armado: Boolean? = null
        private set
    var alarma = false
        private set
    var fallaSensor = false
        private set
    var enCola: Int? = null
        private set
    var rechazados = 0
        private set
    @Volatile var rechazosEsp32 = 0   // comandos inválidos que el ESP32 rechazó (prueba de seguridad)
        private set
    val eventos = ArrayDeque<String>()

    private const val CANAL = "alertas"
    private val principal = Handler(Looper.getMainLooper())
    private val hilo = Executors.newSingleThreadExecutor()
    private val oyentes = mutableSetOf<() -> Unit>()
    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var appContext: Context
    private lateinit var db: LecturasDb
    private var cliente: MqttAsyncClient? = null
    private var nonce: String? = null
    private var secuencia = 0L
    private var syncProgramado = false

    private val clave get() = BuildConfig.MQTT_HMAC_KEY
    private val prefijo get() = BuildConfig.MQTT_PREFIJO

    fun base(context: Context): LecturasDb {
        if (!::db.isInitialized) db = LecturasDb(context)
        return db
    }

    fun agregarOyente(o: () -> Unit) { oyentes.add(o) }
    fun quitarOyente(o: () -> Unit) { oyentes.remove(o) }

    private fun avisar() = principal.post { oyentes.toList().forEach { it() } }

    private fun registrar(texto: String) {
        synchronized(eventos) {
            eventos.addFirst("${formatoHora.format(Date())}  $texto")
            while (eventos.size > 40) eventos.removeLast()
        }
        avisar()
    }

    // Conecta al broker con TLS y busca el ESP32 por su mensaje de presencia firmado
    fun conectar(context: Context) {
        appContext = context.applicationContext
        base(appContext)
        crearCanal()
        if (cliente?.isConnected == true || conexion == Conexion.CONECTANDO) return
        if (clave.length != 64 || BuildConfig.MQTT_URL.isBlank()) {
            registrar("Falta configurar iot.properties")
            return
        }
        conexion = Conexion.CONECTANDO
        avisar()
        hilo.execute { abrirConexion() }
    }

    // Resuelve el broker por IPv4 y abre la conexión MQTT con TLS verificado
    private fun abrirConexion() {
        val url = try {
            TlsIpv4.urlIpv4(BuildConfig.MQTT_URL)
        } catch (e: Exception) {
            conexion = Conexion.DESCONECTADO
            registrar("No se pudo resolver el broker: ${detalle(e)}")
            principal.postDelayed({ if (com.valentina.riesgoscocina.Sesion.correo != null) conectar(appContext) }, 10_000)
            return
        }
        val nombreBroker = TlsIpv4.nombre(BuildConfig.MQTT_URL)
        val c = MqttAsyncClient(
            url,
            "android-" + UUID.randomUUID().toString().take(12),
            MemoryPersistence()
        )
        val opciones = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 15
            keepAliveInterval = 30
            socketFactory = TlsIpv4.Fabrica(nombreBroker)
            isHttpsHostnameVerificationEnabled = false
            sslHostnameVerifier = TlsIpv4.verificador(nombreBroker)
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                conexion = Conexion.CONECTADO
                registrar(if (reconnect) "Reconectado al broker (TLS)" else "Conectado al broker MQTT con TLS")
                suscribir()
            }

            override fun connectionLost(cause: Throwable?) {
                conexion = Conexion.CONECTANDO
                registrar("Conexión perdida, reintentando…")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                procesar(topic, String(message.payload, Charsets.US_ASCII))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        cliente = c
        try {
            c.connect(opciones, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    conexion = Conexion.DESCONECTADO
                    cliente = null
                    Log.e("IotManager", "Fallo MQTT", exception)
                    registrar("No se pudo conectar: ${detalle(exception)}")
                    principal.postDelayed({ if (com.valentina.riesgoscocina.Sesion.correo != null) conectar(appContext) }, 10_000)
                }
            })
        } catch (e: Exception) {
            conexion = Conexion.DESCONECTADO
            registrar("Error MQTT: ${e.message}")
        }
    }

    // Une el mensaje del error con sus causas internas (TLS, DNS, red)
    private fun detalle(e: Throwable?): String {
        val partes = mutableListOf<String>()
        var actual = e
        while (actual != null && partes.size < 4) {
            partes.add("${actual.javaClass.simpleName}: ${actual.message ?: ""}")
            actual = actual.cause
        }
        return if (partes.isEmpty()) "error" else partes.joinToString(" <- ")
    }

    // Plan de contingencia: el usuario puede forzar un nuevo intento sin esperar los 10 s automáticos
    fun reconectarAhora(context: Context) {
        registrar("Reconexión manual solicitada")
        desconectar()
        principal.postDelayed({ conectar(context) }, 500)
    }

    fun desconectar() {
        val c = cliente ?: return
        cliente = null
        hilo.execute {
            try {
                if (c.isConnected) c.disconnect().waitForCompletion(3000)
                c.close()
            } catch (_: Exception) {
            }
        }
        conexion = Conexion.DESCONECTADO
        dispositivo = null
        nonce = null
        avisar()
    }

    private fun suscribir() {
        val c = cliente ?: return
        try {
            c.subscribe("$prefijo+/presence", 0)
            dispositivo?.let { suscribirDispositivo(it) }
        } catch (e: Exception) {
            registrar("Error al suscribir: ${e.message}")
        }
    }

    private fun suscribirDispositivo(b: String) {
        val c = cliente ?: return
        c.subscribe(arrayOf("$b/telemetry", "$b/pending", "$b/events"), intArrayOf(0, 0, 0))
    }

    // Recibe todos los mensajes: solo se aceptan los que tienen una firma HMAC válida
    private fun procesar(topic: String, crudo: String) {
        // Todo mensaje llega cifrado con AES-256-GCM: si no se puede abrir, es ajeno, alterado o de otra clave
        val trama = Protocolo.descifrar(clave, topic, crudo)
        if (trama == null) {
            val b = dispositivo
            if (b != null && topic.startsWith("$b/")) { rechazados++; avisar() }
            return
        }
        if (topic.endsWith("/presence")) {
            presencia(topic.removeSuffix("/presence"), trama)
            return
        }
        val b = dispositivo ?: return
        val n = nonce ?: return
        if (!topic.startsWith("$b/")) return
        if (trama.startsWith("CHALLENGE;")) {
            presencia(b, trama)
            return
        }
        val mensaje = Protocolo.verificar(clave, n, trama)
        if (mensaje == null) {
            rechazados++
            avisar()
            return
        }
        when (topic.removePrefix("$b/")) {
            "telemetry" -> telemetria(b, mensaje)
            "pending" -> pendiente(b, mensaje)
            "events" -> evento(mensaje)
        }
    }

    private fun presencia(b: String, trama: String) {
        val sinMac = trama.substringBefore(";mac=")
        val nuevo = Protocolo.campos(sinMac).second["nonce"] ?: return
        if (!sinMac.startsWith("CHALLENGE;") || Protocolo.verificar(clave, nuevo, trama) == null) return
        if (b == dispositivo && nuevo == nonce) return
        val cambioDispositivo = b != dispositivo
        dispositivo = b
        nonce = nuevo
        secuencia = 0
        registrar("ESP32 verificado: ${b.substringAfterLast('/')} (nonce ${nuevo.take(6)}…)")
        if (cambioDispositivo) {
            try { suscribirDispositivo(b) } catch (e: Exception) { registrar("Error al suscribir: ${e.message}") }
        }
        enviarComando("STATUS")
    }

    private fun telemetria(b: String, mensaje: String) {
        val l = Protocolo.lectura(b, mensaje) ?: return
        actualizarLectura(l)
        if (db.guardar(l)) programarSync()
    }

    // Lectura pendiente: se guarda localmente y solo entonces se confirma con ACK
    private fun pendiente(b: String, mensaje: String) {
        val l = Protocolo.lectura(b, mensaje) ?: return
        if (db.guardar(l)) programarSync()
        if (db.existe(b, l.id)) publicar("$b/commands", Protocolo.ack(clave, nonce ?: return, l.id))
    }

    private fun evento(mensaje: String) {
        val (tipo, c) = Protocolo.campos(mensaje)
        when (tipo) {
            "STATUS" -> {
                armado = c["armed"] == "1"
                alarma = c["alarm"] == "1"
                fallaSensor = c["dht_fault"] == "1"
                enCola = c["queued"]?.toIntOrNull()
                registrar("Estado: ${if (armado == true) "armado" else "desarmado"}, " +
                    "${if (alarma) "alarma activa" else "sin alarma"}, en cola ${enCola ?: 0}")
            }
            "OK" -> {
                val op = c["op"] ?: ""
                when (op) {
                    "ARM" -> armado = true
                    "DISARM" -> { armado = false; alarma = false }
                }
                registrar("Comando aplicado: ${nombreComando(op)}")
            }
            "ACK_OK" -> { enCola = c["queued"]?.toIntOrNull(); avisar() }
            "QUEUE" -> { enCola = c["queued"]?.toIntOrNull(); avisar() }
            "ERR" -> when (c["reason"]) {
                "UNEXPECTED_ACK" -> Unit
                "BAD_COMMAND_MAC" -> { rechazosEsp32++; registrar("RECHAZADO por el ESP32: firma HMAC inválida (BAD_COMMAND_MAC)") }
                "REPLAY_OR_ORDER" -> { rechazosEsp32++; registrar("RECHAZADO por el ESP32: comando repetido o fuera de orden (REPLAY_OR_ORDER)") }
                else -> registrar("ESP32 respondió error: ${c["reason"]}")
            }
            "MQTT_CONNECTED" -> registrar("ESP32 conectado al broker")
        }
    }

    private fun actualizarLectura(l: Lectura) {
        val antes = alarma
        ultimaLectura = l
        armado = l.armado
        alarma = l.alarma
        fallaSensor = l.temp == null
        if (l.alarma && !antes) {
            registrar("ALERTA: ${l.causas().joinToString(", ")}")
            notificar(l)
        } else if (!l.alarma && antes) {
            registrar("Alarma finalizada")
        } else avisar()
    }

    fun enviarComando(op: String): Boolean {
        val b = dispositivo ?: return false
        val n = nonce ?: return false
        if (cliente?.isConnected != true) return false
        secuencia++
        publicar("$b/commands", Protocolo.comando(clave, n, secuencia, op))
        return true
    }

    // Prueba de seguridad (demostración): envía a propósito un comando que el ESP32 DEBE rechazar.
    //  "firma"  -> HMAC inválido (64 ceros)        -> ESP32 responde ERR;reason=BAD_COMMAND_MAC
    //  "replay" -> secuencia ya usada (reenvío)    -> ESP32 responde ERR;reason=REPLAY_OR_ORDER
    fun enviarComandoRechazable(tipo: String): Boolean {
        val b = dispositivo ?: return false
        val n = nonce ?: return false
        if (cliente?.isConnected != true) return false
        val trama = when (tipo) {
            "firma" -> "CMD;${secuencia + 1};ARM;${"0".repeat(64)}"
            else -> Protocolo.comando(clave, n, secuencia, "ARM")
        }
        registrar("Prueba de seguridad enviada: " + if (tipo == "firma") "comando con firma HMAC falsa" else "comando repetido (replay)")
        publicar("$b/commands", trama)
        return true
    }

    private fun publicar(topic: String, texto: String) {
        try {
            val cifrado = Protocolo.cifrar(clave, topic, texto)
            cliente?.publish(topic, MqttMessage(cifrado.toByteArray(Charsets.US_ASCII)).apply {
                qos = 0
                isRetained = false
            })
        } catch (e: Exception) {
            registrar("No se pudo publicar: ${e.message}")
        }
    }

    private fun programarSync() {
        if (syncProgramado) return
        syncProgramado = true
        principal.postDelayed({
            syncProgramado = false
            SyncManager.sincronizar(appContext) { avisar() }
        }, 3000)
    }

    fun nombreComando(op: String) = when (op) {
        "ARM" -> "Armar"
        "DISARM" -> "Desarmar"
        "SILENCE" -> "Silenciar"
        "RESETALARM" -> "Restablecer alarma"
        "STATUS" -> "Consultar estado"
        else -> op
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL, "Alertas de la cocina", NotificationManager.IMPORTANCE_HIGH)
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notificar(l: Lectura) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val abrir = PendingIntent.getActivity(
            appContext, 0, Intent(appContext, MonitorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(appContext, CANAL)
            .setSmallIcon(R.drawable.ic_alerta)
            .setContentTitle("¡Alerta en la cocina!")
            .setContentText(l.causas().joinToString(", "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(1, n)
    }
}
