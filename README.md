# Sistema de prevención de riesgos en la cocina (TI3042)

ESP32 (simulado en Wokwi) → MQTT con TLS + AES-256-GCM → app Android (SQLite) → AWS DynamoDB (Lambda).
Login por HTTPS contra una API PHP (Raspbian en VirtualBox) con MySQL en AWS RDS.

## Dónde está cada requisito

| Requisito | Archivo | Qué buscar |
|---|---|---|
| Login solo por HTTPS | `API_Raspbian/api/comun.php` | respuesta `426 Se requiere HTTPS` |
| Certificado fijado en la app | `RiesgosCocina_Android/app/src/main/res/xml/network_security_config.xml` | `trust-anchors` |
| Contraseñas con hash y consultas seguras | `API_Raspbian/api/registrar.php`, `login.php` | `password_hash`, `password_verify`, `prepare(` |
| Credenciales fuera del repositorio | `.gitignore` de cada proyecto | `iot.properties`, `secrets.h`, `wifi.h`, `db.php`, `servidor.key` |
| Cifrado de los mensajes MQTT | `ESP32_RiesgosCocina/include/cifrado_mqtt.h`, `.../iot/Protocolo.kt` | AES-256-GCM, AAD = tópico |
| Firma HMAC-SHA256 y nonce por arranque | `ESP32_RiesgosCocina/src/main.cpp` (`mqttCommand`), `.../iot/Protocolo.kt` | `hmacSha256`, `CHALLENGE` |
| Rechazo de comandos con autenticación inválida | `main.cpp` (`mqttCommand`), `.../ControlActivity.kt` (Prueba de seguridad) | `BAD_COMMAND_MAC`, `REPLAY_OR_ORDER`, `MQTT_DECRYPT_FAILED` |
| Reconexión automática sin botones | `.../iot/IotManager.kt` (`abrirConexion`) | `isAutomaticReconnect = true`, reintento cada 10 s |
| Plan de contingencia (UI) | `.../MonitorActivity.kt`, `.../iot/IotManager.kt` (`reconectarAhora`) | aviso "Conexión perdida, reintentando…" |
| Recuperación de lecturas tras una desconexión | `main.cpp` (`saveQueue`, `restoreQueue`, cola de 120 en NVS), `IotManager.kt` (`pendiente`) | `MQTT_PENDING_SENT`, `ACK_OK` |
| Respaldo y recuperación en la nube | `.../iot/SyncManager.kt` (`sincronizar`, `recuperar`) | Lambda + DynamoDB |
| Consumo estimado del ESP32 y optimizaciones | `ESP32_RiesgosCocina/include/energia.h`, `main.cpp` (`setEco`, `logEnergia`) | comandos `ENERGY`, `ECO`, `NOECO` en el monitor serial |
| Consumo del teléfono / tema oscuro | `.../EnergiaActivity.kt`, `res/values-night/` | `BatteryManager`, tema claro vs. oscuro |
| Compatibilidad con otras versiones de Android | `RiesgosCocina_Android/app/build.gradle.kts` | `minSdk = 24`, `targetSdk = 37` |

## Pruebas (se muestran en el video)

- **App, pantalla "Pruebas del sistema"** (`PruebasActivity.kt`): ejecuta en vivo la versión de Android, el cifrado AES-256-GCM, la firma HMAC, las credenciales fuera del código, HTTPS con certificado fijado, MQTT con TLS y el rechazo de comandos inválidos por parte del ESP32.
- **ESP32 (Wokwi, terminal serial):** `SELFTEST` (autodiagnóstico de cifrado, firma, rechazo de comandos falsos, cola y energía); `ENERGY`, `NOECO`, `ECO` (consumo); `NETTEST` (corta WiFi/MQTT 12 s y se reconecta solo); `STORETEST` (la cola sobrevive en NVS).
- **App, pantalla Control → Prueba de seguridad:** envía un comando con firma falsa y uno repetido; el ESP32 los rechaza.
- **Emuladores:** Android 7.0 (API 24), Android 17 (API 37) y teléfono físico con Android 14.

## Configuración local (no versionada)

Copiar `include/wifi.example.h` → `include/wifi.h` (solo ESP32 físico); `include/secrets.h` se genera solo al compilar (`tools/provision_secret.py`). En la app, copiar `iot.properties.example` → `iot.properties` y completar los valores. En la API, copiar `config/db.example.php` → `/var/www/config/db.php` con la clave de RDS.
