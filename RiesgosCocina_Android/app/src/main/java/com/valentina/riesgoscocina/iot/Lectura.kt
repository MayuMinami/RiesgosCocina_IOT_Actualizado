package com.valentina.riesgoscocina.iot

data class Lectura(
    val dispositivo: String,
    val id: Long,
    val temp: Double?,
    val hum: Double?,
    val gas: Int,
    val armado: Boolean,
    val alarma: Boolean,
    val recibido: Long,
    val sincronizada: Boolean = false
) {
    companion object {
        const val GAS_LIMITE = 2000
        const val TEMP_LIMITE = 50.0
    }

    // Motivo de la alerta según los mismos umbrales del firmware
    fun causas(): List<String> {
        if (!alarma) return emptyList()
        val lista = mutableListOf<String>()
        if (gas >= GAS_LIMITE) lista.add("Gas elevado ($gas)")
        if (temp != null && temp >= TEMP_LIMITE) lista.add("Temperatura alta (${"%.1f".format(temp)} °C)")
        if (temp == null) lista.add("Falla del sensor de temperatura")
        if (lista.isEmpty()) lista.add("Alarma activa")
        return lista
    }
}
