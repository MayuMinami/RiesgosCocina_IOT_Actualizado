package com.valentina.riesgoscocina

import android.content.Context
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject

object ApiCliente {

    // Siempre HTTPS: la dirección sale de iot.properties (API_URL)
    private val BASE_URL get() = BuildConfig.API_URL

    data class Respuesta(val ok: Boolean, val mensaje: String)

    private var cola: RequestQueue? = null

    private fun cola(context: Context): RequestQueue =
        cola ?: Volley.newRequestQueue(context.applicationContext).also { cola = it }

    // Inicia sesión
    fun login(context: Context, correo: String, password: String, alTerminar: (Respuesta) -> Unit) =
        enviar(context, "login.php", correo, password, alTerminar)

    // Registra un usuario
    fun registrar(context: Context, correo: String, password: String, alTerminar: (Respuesta) -> Unit) =
        enviar(context, "registrar.php", correo, password, alTerminar)

    // Elimina un usuario
    fun eliminar(context: Context, correo: String, password: String, alTerminar: (Respuesta) -> Unit) =
        enviar(context, "eliminar.php", correo, password, alTerminar)

    // Envía la petición a la API
    private fun enviar(
        context: Context,
        endpoint: String,
        correo: String,
        password: String,
        alTerminar: (Respuesta) -> Unit
    ) {
        val peticion = object : StringRequest(
            Request.Method.POST,
            BASE_URL + endpoint,
            { cuerpo -> alTerminar(leerJson(cuerpo, context)) },
            { error ->
                val cuerpo = error.networkResponse?.data?.let { String(it, Charsets.UTF_8) }
                alTerminar(
                    if (cuerpo != null) leerJson(cuerpo, context)
                    else Respuesta(false, context.getString(R.string.error_conexion))
                )
            }
        ) {
            override fun getParams(): MutableMap<String, String> =
                mutableMapOf("correo" to correo, "password" to password)
        }

        peticion.retryPolicy = DefaultRetryPolicy(10_000, 0, 1f)
        peticion.setShouldCache(false)
        cola(context).add(peticion)
    }

    // Lee la respuesta del servidor
    private fun leerJson(cuerpo: String, context: Context): Respuesta = try {
        val json = JSONObject(cuerpo)
        Respuesta(json.optBoolean("ok", false), json.optString("mensaje", ""))
    } catch (e: Exception) {
        Respuesta(false, context.getString(R.string.error_conexion))
    }
}
