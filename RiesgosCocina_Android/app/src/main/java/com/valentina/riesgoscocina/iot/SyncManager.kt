package com.valentina.riesgoscocina.iot

import android.content.Context
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.valentina.riesgoscocina.BuildConfig
import com.valentina.riesgoscocina.Sesion
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.URLEncoder
import java.util.concurrent.Executors

// Envía a Amazon DynamoDB (vía AWS Lambda) las lecturas guardadas que aún no se han subido
object SyncManager {

    private const val LOTE = 50
    private val hilo = Executors.newSingleThreadExecutor()
    private var cola: RequestQueue? = null
    @Volatile private var enCurso = false
    var ultimoResultado: String = "Sin sincronizar todavía"
        private set

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun sincronizar(context: Context, alTerminar: (String) -> Unit) {
        if (enCurso) return
        if (BuildConfig.SYNC_URL.isBlank()) {
            ultimoResultado = "Falta SYNC_URL en iot.properties"
            alTerminar(ultimoResultado)
            return
        }
        enCurso = true
        val app = context.applicationContext
        val db = IotManager.base(app)
        val q = cola ?: Volley.newRequestQueue(app).also { cola = it }

        hilo.execute {
            val pendientes = db.pendientes(LOTE)
            if (pendientes.isEmpty()) {
                enCurso = false
                ultimoResultado = "Todo sincronizado con DynamoDB"
                alTerminar(ultimoResultado)
                return@execute
            }
            val dispositivo = pendientes.first().dispositivo
            val lote = pendientes.filter { it.dispositivo == dispositivo }
            val cuerpo = JSONObject().apply {
                put("dispositivo", dispositivo)
                put("usuario", Sesion.correo ?: "")
                put("lecturas", JSONArray().apply {
                    lote.forEach { l ->
                        put(JSONObject().apply {
                            put("id", l.id)
                            put("temp", l.temp ?: JSONObject.NULL)
                            put("hum", l.hum ?: JSONObject.NULL)
                            put("gas", l.gas)
                            put("armado", l.armado)
                            put("alarma", l.alarma)
                            put("recibido", iso.format(Date(l.recibido)))
                        })
                    }
                })
            }
            val peticion = object : JsonObjectRequest(
                Request.Method.POST, BuildConfig.SYNC_URL, cuerpo,
                { r ->
                    hilo.execute {
                        val ids = r.optJSONArray("guardadas")
                        val lista = (0 until (ids?.length() ?: 0)).map { ids!!.getLong(it) }
                        db.marcarSincronizadas(dispositivo, lista)
                        enCurso = false
                        val restantes = db.contar(true)
                        ultimoResultado = "Subidas ${lista.size} lecturas a DynamoDB" +
                            if (restantes > 0) " ($restantes pendientes)" else ""
                        alTerminar(ultimoResultado)
                        if (restantes > 0) sincronizar(app, alTerminar)
                    }
                },
                { e ->
                    enCurso = false
                    ultimoResultado = "Sin conexión con AWS, se reintentará (${e.networkResponse?.statusCode ?: "red"})"
                    alTerminar(ultimoResultado)
                }
            ) {
                override fun getHeaders(): MutableMap<String, String> =
                    mutableMapOf("x-api-token" to BuildConfig.SYNC_TOKEN)
            }
            peticion.retryPolicy = DefaultRetryPolicy(15_000, 0, 1f)
            peticion.setShouldCache(false)
            q.add(peticion)
        }
    }

    // Recupera desde DynamoDB las últimas lecturas del dispositivo y las guarda en el teléfono
    fun recuperar(context: Context, alTerminar: (String) -> Unit) {
        if (BuildConfig.SYNC_URL.isBlank()) {
            ultimoResultado = "Falta SYNC_URL en iot.properties"
            alTerminar(ultimoResultado)
            return
        }
        val app = context.applicationContext
        val db = IotManager.base(app)
        hilo.execute {
            val dispositivo = IotManager.dispositivo ?: db.ultimoDispositivo()
            if (dispositivo == null) {
                ultimoResultado = "Aún no se conoce el dispositivo: espera a que el ESP32 esté en línea"
                alTerminar(ultimoResultado)
                return@execute
            }
            val q = cola ?: Volley.newRequestQueue(app).also { cola = it }
            val url = BuildConfig.SYNC_URL + "?dispositivo=" + URLEncoder.encode(dispositivo, "UTF-8") + "&limite=100"
            val peticion = object : JsonObjectRequest(
                Request.Method.GET, url, null,
                { r ->
                    hilo.execute {
                        val lista = r.optJSONArray("lecturas") ?: JSONArray()
                        var nuevas = 0
                        for (i in 0 until lista.length()) {
                            val o = lista.getJSONObject(i)
                            val fecha = try { iso.parse(o.optString("recibido"))?.time } catch (_: Exception) { null }
                            val l = Lectura(
                                dispositivo = dispositivo,
                                id = o.getLong("id"),
                                temp = if (o.has("temp") && !o.isNull("temp")) o.getDouble("temp") else null,
                                hum = if (o.has("hum") && !o.isNull("hum")) o.getDouble("hum") else null,
                                gas = o.optInt("gas", 0),
                                armado = o.optBoolean("armado", false),
                                alarma = o.optBoolean("alarma", false),
                                recibido = fecha ?: System.currentTimeMillis(),
                                sincronizada = true
                            )
                            if (db.guardarDeNube(l)) nuevas++
                        }
                        ultimoResultado = "Recuperadas ${lista.length()} lecturas desde DynamoDB ($nuevas nuevas en el teléfono)"
                        alTerminar(ultimoResultado)
                    }
                },
                { e ->
                    ultimoResultado = "No se pudo leer DynamoDB (${e.networkResponse?.statusCode ?: "sin red"})"
                    alTerminar(ultimoResultado)
                }
            ) {
                override fun getHeaders(): MutableMap<String, String> =
                    mutableMapOf("x-api-token" to BuildConfig.SYNC_TOKEN)
            }
            peticion.retryPolicy = DefaultRetryPolicy(15_000, 0, 1f)
            peticion.setShouldCache(false)
            q.add(peticion)
        }
    }
}
