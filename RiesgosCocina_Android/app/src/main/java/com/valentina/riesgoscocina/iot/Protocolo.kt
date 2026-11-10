package com.valentina.riesgoscocina.iot

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Firma y verificación de mensajes según el contrato ESP32 ↔ Android
object Protocolo {

    val OPERACIONES = setOf("STATUS", "GETQUEUE", "ARM", "DISARM", "SILENCE", "RESETALARM")

    fun hmac(clave: String, mensaje: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clave.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(mensaje.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
    }

    // ---- Cifrado de los mensajes MQTT: AES-256-GCM (mismo formato que el ESP32, ver cifrado_mqtt.h) ----
    // Trama: "E1;" + Base64( IV[12] | texto cifrado | etiqueta[16] ); AAD = nombre completo del tema.
    // Clave AES = HMAC-SHA256(secreto compartido, "riesgos-aes-key-v1").
    private val aleatorio = SecureRandom()

    private fun claveAes(clave: String): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clave.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return SecretKeySpec(mac.doFinal("riesgos-aes-key-v1".toByteArray(Charsets.US_ASCII)), "AES")
    }

    fun cifrar(clave: String, tema: String, plano: String): String {
        val iv = ByteArray(12).also { aleatorio.nextBytes(it) }
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, claveAes(clave), GCMParameterSpec(128, iv))
        cifrador.updateAAD(tema.toByteArray(Charsets.US_ASCII))
        val cifrado = cifrador.doFinal(plano.toByteArray(Charsets.US_ASCII))
        return "E1;" + Base64.encodeToString(iv + cifrado, Base64.NO_WRAP)
    }

    // Devuelve el texto plano, o null si la trama no es válida, fue alterada o es de otro tema/clave
    fun descifrar(clave: String, tema: String, trama: String): String? {
        if (!trama.startsWith("E1;")) return null
        return try {
            val bin = Base64.decode(trama.substring(3), Base64.DEFAULT)
            if (bin.size < 12 + 16) return null
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
            cifrador.init(Cipher.DECRYPT_MODE, claveAes(clave), GCMParameterSpec(128, bin.copyOfRange(0, 12)))
            cifrador.updateAAD(tema.toByteArray(Charsets.US_ASCII))
            String(cifrador.doFinal(bin, 12, bin.size - 12), Charsets.US_ASCII)
        } catch (e: Exception) {
            null
        }
    }

    // Separa "mensaje;mac=<hex>" y comprueba la firma en tiempo constante
    fun verificar(clave: String, nonce: String, trama: String): String? {
        val corte = trama.lastIndexOf(";mac=")
        if (corte < 0) return null
        val mensaje = trama.substring(0, corte)
        val recibida = trama.substring(corte + 5).lowercase()
        if (recibida.length != 64) return null
        val esperada = hmac(clave, "$nonce|$mensaje")
        val ok = MessageDigest.isEqual(
            esperada.toByteArray(Charsets.US_ASCII),
            recibida.toByteArray(Charsets.US_ASCII)
        )
        return if (ok) mensaje else null
    }

    fun comando(clave: String, nonce: String, seq: Long, op: String): String {
        require(op in OPERACIONES) { "Comando no permitido" }
        return "CMD;$seq;$op;${hmac(clave, "$nonce|$seq|$op")}"
    }

    fun ack(clave: String, nonce: String, id: Long): String =
        "ACK;$id;${hmac(clave, "$nonce|ACK|$id")}"

    // Convierte "TIPO;a=1;b=2" en el tipo y un mapa de campos
    fun campos(mensaje: String): Pair<String, Map<String, String>> {
        val partes = mensaje.split(";")
        val mapa = partes.drop(1).mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        return partes.first() to mapa
    }

    fun lectura(dispositivo: String, mensaje: String): Lectura? {
        val (tipo, c) = campos(mensaje)
        if (tipo != "DATA") return null
        val id = c["id"]?.toLongOrNull() ?: return null
        return Lectura(
            dispositivo = dispositivo,
            id = id,
            temp = c["temp"]?.toDoubleOrNull(),
            hum = c["hum"]?.toDoubleOrNull(),
            gas = c["gas"]?.toIntOrNull() ?: 0,
            armado = c["armed"] == "1",
            alarma = c["alarm"] == "1",
            recibido = System.currentTimeMillis()
        )
    }
}
