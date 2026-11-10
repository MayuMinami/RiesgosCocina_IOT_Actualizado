package com.valentina.riesgoscocina

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.valentina.riesgoscocina.iot.IotManager

class HomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CORREO = "correo"
    }

    private lateinit var tvConexion: TextView
    private val refrescar: () -> Unit = { mostrarConexion() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)
        val raiz = findViewById<View>(R.id.home)
        val pad = raiz.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + barras.left, pad + barras.top, pad + barras.right, pad + barras.bottom)
            insets
        }

        val correo = intent.getStringExtra(EXTRA_CORREO) ?: Sesion.correo
        if (correo.isNullOrBlank()) {
            volverAlLogin()
            return
        }
        Sesion.correo = correo
        findViewById<TextView>(R.id.tvCorreo).text = correo
        tvConexion = findViewById(R.id.tvConexion)

        findViewById<MaterialCardView>(R.id.cardMonitor).setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardControl).setOnClickListener {
            startActivity(Intent(this, ControlActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardHistorial).setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardEnergia).setOnClickListener {
            startActivity(Intent(this, EnergiaActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardPruebas).setOnClickListener {
            startActivity(Intent(this, PruebasActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnReconectar).setOnClickListener {
            IotManager.reconectarAhora(this)
        }
        findViewById<MaterialButton>(R.id.btnCerrarSesion).setOnClickListener {
            IotManager.desconectar()
            Sesion.correo = null
            volverAlLogin()
        }

        pedirPermisoNotificaciones()
        IotManager.conectar(this)
    }

    override fun onStart() {
        super.onStart()
        if (::tvConexion.isInitialized) {
            IotManager.agregarOyente(refrescar)
            mostrarConexion()
        }
    }

    override fun onStop() {
        IotManager.quitarOyente(refrescar)
        super.onStop()
    }

    // Muestra el estado de la conexión MQTT y del ESP32
    private fun mostrarConexion() {
        tvConexion.text = when {
            IotManager.conexion != IotManager.Conexion.CONECTADO -> "● Conectando al broker MQTT…"
            IotManager.dispositivo == null -> "● Broker conectado (TLS) · buscando ESP32…"
            else -> "● ESP32 en línea · MQTT con TLS"
        }
        val color = if (IotManager.dispositivo != null && IotManager.conexion == IotManager.Conexion.CONECTADO)
            R.color.verde_ok else R.color.borgona_suave
        tvConexion.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    // Cierra sesión
    private fun volverAlLogin() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
