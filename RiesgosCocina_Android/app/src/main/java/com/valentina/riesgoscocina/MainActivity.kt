package com.valentina.riesgoscocina

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var tilCorreo: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etCorreo: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnIngresar: MaterialButton
    private lateinit var btnGuardar: MaterialButton
    private lateinit var btnEliminar: MaterialButton
    private lateinit var progreso: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        tilCorreo = findViewById(R.id.tilCorreo)
        tilPassword = findViewById(R.id.tilPassword)
        etCorreo = findViewById(R.id.etCorreo)
        etPassword = findViewById(R.id.etPassword)
        btnIngresar = findViewById(R.id.btnIngresar)
        btnGuardar = findViewById(R.id.btnGuardar)
        btnEliminar = findViewById(R.id.btnEliminar)
        progreso = findViewById(R.id.progreso)

        btnIngresar.setOnClickListener { ingresar() }
        btnGuardar.setOnClickListener { guardar() }
        btnEliminar.setOnClickListener { confirmarEliminar() }
    }

    // Inicia sesión
    private fun ingresar() {
        val (correo, password) = leerCampos() ?: return
        cargando(true)
        ApiCliente.login(this, correo, password) { r ->
            cargando(false)
            if (r.ok) {
                etPassword.text?.clear()
                Sesion.correo = correo
                startActivity(
                    Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_CORREO, correo)
                )
                finish()
            } else {
                mostrar(r.mensaje)
            }
        }
    }

    // Guarda un usuario
    private fun guardar() {
        val (correo, password) = leerCampos() ?: return
        cargando(true)
        ApiCliente.registrar(this, correo, password) { r ->
            cargando(false)
            mostrar(r.mensaje)
        }
    }

    // Elimina un usuario
    private fun confirmarEliminar() {
        val (correo, password) = leerCampos() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirmar_eliminar_titulo)
            .setMessage(getString(R.string.confirmar_eliminar_mensaje, correo))
            .setNegativeButton(R.string.cancelar, null)
            .setPositiveButton(R.string.si_eliminar) { _, _ ->
                cargando(true)
                ApiCliente.eliminar(this, correo, password) { r ->
                    cargando(false)
                    mostrar(r.mensaje)
                    if (r.ok) {
                        etCorreo.text?.clear()
                        etPassword.text?.clear()
                    }
                }
            }
            .show()
    }

    // Valida los campos
    private fun leerCampos(): Pair<String, String>? {
        val correo = etCorreo.text?.toString()?.trim()?.lowercase().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        tilCorreo.error = null
        tilPassword.error = null
        var valido = true

        if (!Patterns.EMAIL_ADDRESS.matcher(correo).matches()) {
            tilCorreo.error = getString(R.string.error_correo)
            valido = false
        }
        if (password.length < 8) {
            tilPassword.error = getString(R.string.error_password)
            valido = false
        }
        return if (valido) correo to password else null
    }

    private fun cargando(activo: Boolean) {
        progreso.visibility = if (activo) View.VISIBLE else View.GONE
        btnIngresar.isEnabled = !activo
        btnGuardar.isEnabled = !activo
        btnEliminar.isEnabled = !activo
    }

    private fun mostrar(mensaje: String) =
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
}
