// Estimación del consumo de energía del ESP32 y sus periféricos (modelo, no medición).
// Wokwi no mide corriente, así que se calcula con valores típicos de las hojas de datos
// y con los tiempos REALES de funcionamiento (alarma, LED, buzzer) que lleva el firmware.
//
// Fuentes de los valores (hojas de datos):
//  - ESP32 Series Datasheet (Espressif), "Current Consumption":
//      Wi-Fi Rx (radio escuchando)        ~ 95-100 mA
//      Wi-Fi Tx 802.11b 20 dBm            ~ 240 mA (peor caso)
//      Modem-sleep, CPU a 80 MHz          ~ 20-31 mA   (se usa 25)
//  - Beacon de Wi-Fi cada 102,4 ms; en modem-sleep la radio despierta ~5 ms por baliza.
//  - DHT22 (AOSONG): medición ~1,5 mA, reposo ~0,05 mA.
//  - LED con resistencia de 220 ohm a 3,3 V: ~6 mA. Buzzer activo: ~30 mA.
//  - Módulo de gas MQ-2 real: calentador ~150 mA a 5 V (en el simulador es un potenciómetro).
//
// Perfil SIN optimizar: CPU a 240 MHz y Wi-Fi siempre escuchando (sin modem-sleep).
// Perfil OPTIMIZADO ("ECO"): CPU a 80 MHz y WiFi.setSleep(true) (modem-sleep).
#pragma once
#include <stdint.h>
#include <stdio.h>

namespace energia {

static const float WIFI_RX_MA = 95.0f;
static const float WIFI_TX_MA = 240.0f;
static const float MODEM_SLEEP_80MHZ_MA = 25.0f;
static const float BALIZA_ACTIVA = 5.0f / 102.4f;  // fracción de tiempo con la radio despierta
static const float TX_ACTIVA = 0.008f / 3.0f;       // ~8 ms transmitiendo por cada lectura (cada 3 s)
static const float DHT_MA = 1.5f * (0.25f / 3.0f) + 0.05f * (1.0f - 0.25f / 3.0f);
static const float LED_MA = 6.0f;
static const float BUZZER_MA = 30.0f;
static const float MQ2_REAL_MA = 150.0f;
static const float BATERIA_MAH = 2000.0f;           // batería de ejemplo para estimar la autonomía

// Corriente media del ESP32 conectado (sin LED/buzzer) según el perfil
static inline float corrienteBase(bool eco) {
  float base = eco ? MODEM_SLEEP_80MHZ_MA + BALIZA_ACTIVA * (WIFI_RX_MA - MODEM_SLEEP_80MHZ_MA)
                   : WIFI_RX_MA;
  return base + TX_ACTIVA * (WIFI_TX_MA - base) + DHT_MA;
}

struct Modelo {
  bool eco = true;
  uint32_t ultimoMs = 0;
  uint32_t totalMs = 0;
  double mAhActual = 0;  // con el perfil que haya estado activo en cada momento
  double mAhSinOpt = 0;  // lo que habría gastado sin optimizaciones, con la misma alarma/LED/buzzer

  // Acumula el consumo desde la última llamada. Se llama cada vuelta del loop.
  void actualizar(uint32_t ahoraMs, bool led, bool buzzer) {
    uint32_t dt = ahoraMs - ultimoMs;
    ultimoMs = ahoraMs;
    if (dt == 0 || dt > 5000) return;  // ignora el primer arranque o pausas largas
    float extra = (led ? LED_MA : 0.0f) + (buzzer ? BUZZER_MA : 0.0f);
    double horas = dt / 3600000.0;
    mAhActual += (corrienteBase(eco) + extra) * horas;
    mAhSinOpt += (corrienteBase(false) + extra) * horas;
    totalMs += dt;
  }

  void fijarPerfil(uint32_t ahoraMs, bool nuevoEco) {
    actualizar(ahoraMs, false, false);
    eco = nuevoEco;
  }

  // Escribe una línea de resumen y otra con el módulo MQ-2 real. Devuelve la longitud total.
  int resumen(char *salida, size_t capacidad, int cpuMhz) const {
    double h = totalMs / 3600000.0;
    if (h <= 0) h = 1e-9;
    double mA = mAhActual / h, mAsin = mAhSinOpt / h;
    double ahorro = mAsin > 0 ? (1.0 - mA / mAsin) * 100.0 : 0.0;
    double mAmq = mA + MQ2_REAL_MA, mAsinMq = mAsin + MQ2_REAL_MA;
    double ahorroMq = (1.0 - mAmq / mAsinMq) * 100.0;
    return snprintf(salida, capacidad,
      "ENERGY;perfil=%s;cpu_mhz=%d;wifi_sleep=%d;mA_perfil_ahora=%.1f;uptime_s=%lu;mA_medio=%.1f;mA_sin_optimizar=%.1f;"
      "ahorro_pct=%.0f;mAh_usados=%.3f;mAh_sin_optimizar=%.3f;autonomia_h=%.0f;autonomia_sin_opt_h=%.0f\n"
      "ENERGY_MQ2;con_modulo_MQ2_real=+%.0fmA;mA_medio=%.1f;mA_sin_optimizar=%.1f;ahorro_pct=%.0f;"
      "autonomia_h=%.0f;autonomia_sin_opt_h=%.0f",
      eco ? "ECO" : "SIN_OPTIMIZAR", cpuMhz, eco ? 1 : 0, corrienteBase(eco), (unsigned long)(totalMs / 1000), mA, mAsin,
      ahorro, mAhActual, mAhSinOpt, BATERIA_MAH / mA, BATERIA_MAH / mAsin,
      (double)MQ2_REAL_MA, mAmq, mAsinMq, ahorroMq, BATERIA_MAH / mAmq, BATERIA_MAH / mAsinMq);
  }
};

}  // namespace energia
