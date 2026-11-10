#include <Arduino.h>
#ifdef WOKWI_SIMULATION
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <time.h>
#include <Preferences.h>
#include "mqtt_root_ca.h"
#include <PubSubClient.h>
#endif
#include <BluetoothSerial.h>
#include <DHT.h>
#include <mbedtls/md.h>
#include <math.h>
#include <cctype>
#include <cstring>
#include <cstddef>
#include <cstdlib>
#include <esp_system.h>
#include "secrets.h"
#include "cifrado_mqtt.h"
#include "energia.h"

// Prototipo educativo: alarma ambiental, NO es un detector certificado de incendios.
// Transporte: Bluetooth Classic SPP (ESP32 original, NO ESP32-C3/S3).
// Los comandos Bluetooth exigen autenticacion HMAC y secuencia creciente.
// El monitor USB es solo para diagnostico fisico: NO es un canal remoto seguro.
static constexpr int PIN_DHT = 15;
static constexpr int PIN_GAS = 34;
static constexpr int PIN_LED = 2;
static constexpr int PIN_BUZZER = 4;
static constexpr uint32_t SAMPLE_MS = 3000;
static constexpr uint32_t RETRY_MS = 3500;
static constexpr uint32_t AUTH_TIMEOUT_MS = 20000;
static constexpr uint32_t IDLE_TIMEOUT_MS = 300000;
static constexpr size_t BUFFER_CAP = 120;
static constexpr uint32_t PERSIST_EVERY_SAMPLES = 5; // Reduce escrituras flash (15 s)
static constexpr size_t MAX_LINE = 120;

BluetoothSerial bt;
DHT dht(PIN_DHT, DHT22);
struct Reading {
  uint32_t id;
  float temp;
  float humidity;
  int gas;
  bool validDht;
  bool armed;
  bool alarm;
};
Reading pending[BUFFER_CAP];
size_t head = 0, count = 0;
uint32_t nextId = 1, overflowCount = 0;
uint32_t lastSample = 0, lastTransmit = 0, lastActive = 0;
uint32_t connectionStart = 0, lastSequence = 0;
bool armed = true, silenced = false, currentAlarm = false;
static energia::Modelo energiaModelo;  // estimación de consumo (ver include/energia.h)
// DHT22: conservar la ultima alarma termica durante lecturas fallidas.
// Dos fallos consecutivos activan alarma de fallo de sensor (fail-safe).
bool lastValidTempHigh = false, dhtFault = false;
uint8_t dhtConsecutiveFailures = 0;
bool wasConnected = false, authorized = false;
char nonce[17] = {0};
char btLine[MAX_LINE + 1] = {0};
char usbLine[MAX_LINE + 1] = {0};
size_t btLen = 0, usbLen = 0;
Reading latest = {};
#ifdef WOKWI_SIMULATION
// La cola se guarda en la particion NVS de la flash del ESP32.
// Se usa version, CRC32 y validacion de rangos para rechazar snapshots corruptos.
static constexpr uint32_t STORE_MAGIC = 0x52434B31; // RCK1
static constexpr uint16_t STORE_VERSION = 1;
struct PersistedQueue {
  uint32_t magic;
  uint16_t version;
  uint16_t size;
  uint32_t nextId;
  uint32_t overflowCount;
  uint32_t items;
  Reading records[BUFFER_CAP];
  uint32_t crc;
};
// Snapshot estatico: evita reservar ~3 KB en la pila durante escritura/lectura NVS.
static PersistedQueue nvsSnapshot = {};
static uint32_t samplesSinceSave = 0;
static bool storageReady = false;
static uint32_t crc32Buffer(const uint8_t *data, size_t len) {
  uint32_t crc = 0xFFFFFFFFUL;
  for (size_t i = 0; i < len; ++i) {
    crc ^= data[i];
    for (uint8_t b = 0; b < 8; ++b)
      crc = (crc >> 1) ^ ((crc & 1UL) ? 0xEDB88320UL : 0UL);
  }
  return ~crc;
}
static bool saveQueue() {
  if (!storageReady) return false;
  // Struct inicializada a cero: CRC consistente incluso con padding.
  PersistedQueue &snapshot = nvsSnapshot;
  memset(&snapshot, 0, sizeof(snapshot));
  snapshot.magic = STORE_MAGIC;
  snapshot.version = STORE_VERSION;
  snapshot.size = sizeof(PersistedQueue);
  snapshot.nextId = nextId;
  snapshot.overflowCount = overflowCount;
  snapshot.items = count;
  for (size_t i = 0; i < count; ++i)
    snapshot.records[i] = pending[(head + i) % BUFFER_CAP];
  snapshot.crc = crc32Buffer((const uint8_t *)&snapshot, offsetof(PersistedQueue, crc));
  Preferences store;
  if (!store.begin("rc_iot", false)) {
    Serial.println("STORE_ERROR;reason=NVS_OPEN"); return false;
  }
  size_t written = store.putBytes("queue_v1", &snapshot, sizeof(snapshot));
  store.end();
  if (written == sizeof(snapshot)) {
    samplesSinceSave = 0;
    Serial.println("STORE_SAVED;queued=" + String(count));
    return true;
  }
  Serial.println("STORE_ERROR;reason=NVS_WRITE");
  return false;
}
static void restoreQueue() {
  Preferences store;
  if (!store.begin("rc_iot", false)) {
    Serial.println("STORE_ERROR;reason=NVS_OPEN"); return;
  }
  storageReady = true;
  size_t n = store.getBytesLength("queue_v1");
  if (n == 0) { store.end(); Serial.println("STORE_EMPTY"); return; }
  if (n != sizeof(PersistedQueue)) {
    store.end(); Serial.println("STORE_ERROR;reason=BAD_SIZE"); return;
  }
  PersistedQueue &snapshot = nvsSnapshot;
  memset(&snapshot, 0, sizeof(snapshot));
  size_t read = store.getBytes("queue_v1", &snapshot, sizeof(snapshot));
  store.end();
  if (read != sizeof(snapshot) || snapshot.magic != STORE_MAGIC ||
      snapshot.version != STORE_VERSION || snapshot.size != sizeof(PersistedQueue) ||
      snapshot.items > BUFFER_CAP || snapshot.nextId == 0 ||
      (snapshot.items && snapshot.records[snapshot.items-1].id >= snapshot.nextId) ||
      snapshot.crc != crc32Buffer((const uint8_t *)&snapshot, offsetof(PersistedQueue, crc))) {
    Serial.println("STORE_ERROR;reason=BAD_CRC_OR_HEADER"); return;
  }
  // Verificar que los IDs de la cola estan estrictamente ordenados.
  for (size_t i = 0; i < snapshot.items; ++i) {
    if (snapshot.records[i].id == 0 ||
        (i > 0 && snapshot.records[i].id <= snapshot.records[i-1].id)) {
      Serial.println("STORE_ERROR;reason=BAD_IDS"); return;
    }
  }
  head = 0;
  count = snapshot.items;
  nextId = snapshot.nextId;
  overflowCount = snapshot.overflowCount;
  for (size_t i = 0; i < count; ++i) pending[i] = snapshot.records[i];
  Serial.println("STORE_RESTORED;queued=" + String(count) + ";next=" + String(nextId));
}
#endif
#ifdef WOKWI_SIMULATION
// Wokwi: Wi-Fi/MQTT en lugar de Bluetooth Classic; broker PUBLICO de demostracion.
#ifdef WIFI_REAL
// ESP32 fisico: red WiFi real definida en include/wifi.h (no se sube a Git)
#include "wifi.h"
static const char *WIFI_SSID = WIFI_RED_NOMBRE;
static const char *WIFI_PASS = WIFI_RED_CLAVE;
static constexpr int32_t WIFI_CANAL = 0;
#else
static const char *WIFI_SSID = "Wokwi-GUEST";
static const char *WIFI_PASS = "";
static constexpr int32_t WIFI_CANAL = 6;
#endif
static const char *MQTT_HOST = "broker.hivemq.com";
static constexpr uint16_t MQTT_PORT = 8883; // MQTT sobre TLS con CA validada
static constexpr uint32_t WIFI_RETRY_MS = 8000;
static constexpr uint32_t MQTT_RETRY_MS = 6000;
static constexpr uint32_t MQTT_RESEND_MS = 4500;
WiFiClientSecure netClient;
PubSubClient mqtt(netClient);
String mqttBase, mqttTelemetryTopic, mqttPendingTopic, mqttCommandsTopic, mqttEventsTopic, mqttPresenceTopic;
uint32_t lastWifiTry = 0, lastMqttTry = 0, lastMqttResend = 0;
uint32_t mqttLastSequence = 0;
uint32_t lastWiFiState = 0;
uint32_t netTestUntil = 0; // simulacion controlada de caida de red
bool previousMqttConnected = false;
String mqttNonce;
#endif

static String ultimoUsb;  // ultima linea escrita (la usa SELFTEST para comprobar resultados)
static void logUsb(const String &s) { ultimoUsb = s; Serial.println(s); }
static void sendBt(const String &s) {
#ifndef WOKWI_SIMULATION
  if (bt.hasClient()) bt.println(s);
#else
  (void)s;
#endif
}
static bool isDigits(const String &s) {
  if (s.isEmpty() || s.length() > 10) return false;
  for (size_t i = 0; i < s.length(); i++) if (!isDigit(s[i])) return false;
  return true;
}
static bool isHex64(const String &s) {
  if (s.length() != 64) return false;
  for (size_t i = 0; i < s.length(); i++) if (!isxdigit((unsigned char)s[i])) return false;
  return true;
}
static bool constantTimeEquals(const String &a, const String &b) {
  if (a.length() != b.length()) return false;
  unsigned char diff = 0;
  for (size_t i = 0; i < a.length(); i++) diff |= (unsigned char)(a[i] ^ b[i]);
  return diff == 0;
}
static String hmacSha256(const String &message) {
  unsigned char mac[32] = {0};
  const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (!info) return "";
  int rc = mbedtls_md_hmac(info,
       reinterpret_cast<const unsigned char *>(APP_SHARED_SECRET), strlen(APP_SHARED_SECRET),
       reinterpret_cast<const unsigned char *>(message.c_str()), message.length(), mac);
  if (rc != 0) return "";
  const char digits[] = "0123456789abcdef";
  char hex[65];
  for (int i = 0; i < 32; i++) {
    hex[i * 2] = digits[mac[i] >> 4];
    hex[i * 2 + 1] = digits[mac[i] & 15];
  }
  hex[64] = '\0';
  return String(hex);
}
static void appendReading(const Reading &r) {
  if (count == BUFFER_CAP) {
    head = (head + 1) % BUFFER_CAP;
    --count;
    ++overflowCount;
  }
  pending[(head + count) % BUFFER_CAP] = r;
  ++count;
}
static String readingMessage(const Reading &r) {
  String s = "DATA;id=" + String(r.id);
  s += ";temp=" + (r.validDht ? String(r.temp, 1) : String("null"));
  s += ";hum=" + (r.validDht ? String(r.humidity, 1) : String("null"));
  s += ";gas=" + String(r.gas);
  s += ";armed=" + String(r.armed ? 1 : 0);
  s += ";alarm=" + String(r.alarm ? 1 : 0);
  s += ";lost=" + String(overflowCount);
  if (authorized) s += ";mac=" + hmacSha256(String(nonce) + "|" + s);
  return s;
}
// Solo la primera lectura pendiente se confirma; evita descartar datos no recibidos.
static bool confirmOldest(uint32_t id) {
  if (count == 0 || pending[head].id != id) return false;
  head = (head + 1) % BUFFER_CAP;
  --count;
  lastTransmit = 0;
#ifdef WOKWI_SIMULATION
  saveQueue(); // Persistir cada confirmacion, no revivir registros ya confirmados.
#endif
  return true;
}
// GETQUEUE por USB muestra hasta 10 entradas, para no saturar Wokwi.
// En Bluetooth solo muestra la primera (stop-and-wait con ACK autenticado).
static void replyQueue(bool remote) {
  String summary = "QUEUE;queued=" + String(count) + ";lost=" + String(overflowCount);
  if (count) summary += ";oldest=" + String(pending[head].id);
  if (remote) sendBt(summary); else logUsb(summary);
  const size_t maxItems = remote ? 1 : 10;
  size_t show = count < maxItems ? count : maxItems;
  for (size_t i = 0; i < show; ++i) {
    // HMAC solo cuando hay una sesion Bluetooth autenticada.
    const Reading &r = pending[(head + i) % BUFFER_CAP];
    String item = readingMessage(r);
    if (remote) sendBt(item); else logUsb(item);
  }
  String end = "QUEUE_END;shown=" + String(show) + ";remaining=" + String(count - show);
  if (remote) sendBt(end); else logUsb(end);
}
static void replyStatus(bool remote, uint32_t seq = 0) {
  String s = "STATUS;seq=" + String(seq) + ";armed=" + String(armed ? 1 : 0);
  s += ";alarm=" + String(currentAlarm ? 1 : 0);
  s += ";silenced=" + String(silenced ? 1 : 0);
  s += ";queued=" + String(count);
  s += ";lost=" + String(overflowCount);
  s += ";dht_fault=" + String(dhtFault ? 1 : 0);
  if (remote) { s += ";mac=" + hmacSha256(String(nonce) + "|" + s); sendBt(s); }
  else logUsb(s);
}
static void performCommand(const String &op, bool remote, uint32_t seq) {
  String result;
  if (op == "ARM") { armed = true; result = "OK"; }
  else if (op == "DISARM") { armed = false; currentAlarm = false; silenced = false; result = "OK"; }
  else if (op == "SILENCE") { silenced = true; result = "OK"; }
  else if (op == "RESETALARM") { silenced = false; result = "OK"; }
  else if (op == "STATUS") { replyStatus(remote, seq); return; }
  else if (op == "GETQUEUE") { replyQueue(remote); return; }
  else if (op == "HELP") {
    result = remote ? "HELP;commands=ARM,DISARM,SILENCE,RESETALARM,STATUS,GETQUEUE,HELP"
                    : "HELP;commands=ARM,DISARM,SILENCE,RESETALARM,STATUS,GETQUEUE,ACK:<id>,HELP";
    if (remote) sendBt(result); else logUsb(result);
    return;
  } else { result = "ERR;reason=UNKNOWN_COMMAND"; }
  result += ";seq=" + String(seq) + ";op=" + op;
  if (remote) sendBt(result); else logUsb(result);
}
static void makeNonce() {
  snprintf(nonce, sizeof(nonce), "%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random());
}
static void resetSession() {
  authorized = false;
  lastSequence = 0;
  btLen = 0;
  lastTransmit = 0;
  connectionStart = millis();
  lastActive = millis();
  makeNonce();
}
static void onBluetoothLine(const String &line) {
  lastActive = millis();
  if (line == "HELLO") {
    if (authorized) { sendBt("ERR;reason=ALREADY_AUTHORIZED"); return; }
    sendBt("CHALLENGE;nonce=" + String(nonce));
    return;
  }
  if (line.startsWith("AUTH;")) {
    if (authorized) { sendBt("ERR;reason=ALREADY_AUTHORIZED"); return; }
    String proof = line.substring(5);
    proof.toLowerCase();
    if (isHex64(proof) && constantTimeEquals(proof, hmacSha256(String(nonce)))) {
      authorized = true;
      sendBt("AUTH_OK;protocol=1");
    } else {
      sendBt("ERR;reason=AUTH_FAILED");
      bt.disconnect();
    }
    return;
  }
  if (!authorized) { sendBt("ERR;reason=AUTH_REQUIRED"); return; }
  if (line.startsWith("ACK;")) {
    int split = line.indexOf(';', 4);
    if (split < 0) { sendBt("ERR;reason=BAD_ACK"); return; }
    String idText = line.substring(4, split);
    String mac = line.substring(split + 1);
    mac.toLowerCase();
    if (!isDigits(idText) || !isHex64(mac) ||
        !constantTimeEquals(mac, hmacSha256(String(nonce) + "|ACK|" + idText))) {
      sendBt("ERR;reason=BAD_ACK_MAC"); return;
    }
    uint32_t id = (uint32_t)strtoul(idText.c_str(), nullptr, 10);
    if (confirmOldest(id)) {
      sendBt("ACK_OK;id=" + String(id) + ";queued=" + String(count));
    } else { sendBt("ERR;reason=UNEXPECTED_ACK"); }
    return;
  }
  // CMD;<seq>;<command>;<hmac> - una secuencia estrictamente creciente por sesion.
  if (!line.startsWith("CMD;")) { sendBt("ERR;reason=BAD_FRAME"); return; }
  int sep = line.indexOf(';', 4);
  if (sep < 0) { sendBt("ERR;reason=BAD_FRAME"); return; }
  String seqText = line.substring(4, sep);
  int macSep = line.indexOf(';', sep + 1);
  if (macSep < 0) { sendBt("ERR;reason=BAD_FRAME"); return; }
  String op = line.substring(sep + 1, macSep);
  String mac = line.substring(macSep + 1);
  mac.toLowerCase();
  if (!isDigits(seqText) || op.isEmpty() || op.indexOf(';') >= 0 || !isHex64(mac)) {
    sendBt("ERR;reason=BAD_FRAME"); return;
  }
  uint32_t seq = (uint32_t)strtoul(seqText.c_str(), nullptr, 10);
  if (seq == 0 || seq <= lastSequence) { sendBt("ERR;reason=REPLAY_OR_ORDER"); return; }
  if (!constantTimeEquals(mac, hmacSha256(String(nonce) + "|" + seqText + "|" + op))) {
    sendBt("ERR;reason=BAD_COMMAND_MAC"); return;
  }
  lastSequence = seq;
  performCommand(op, true, seq);
}
#ifdef WOKWI_SIMULATION
// Firma los mensajes de salida con un MAC por sesion. No aporta cifrado.
static String signedMqttMessage(const String &message) {
  return message + ";mac=" + hmacSha256(mqttNonce + "|" + message);
}
// Publica el mensaje ya firmado, cifrado con AES-256-GCM (el broker publico solo ve bytes ilegibles)
static bool mqttSend(const String &topic, const String &signedMsg, bool retained = false) {
  char frame[640];
  int n = cifradoSellar(APP_SHARED_SECRET, topic.c_str(), (const uint8_t *)signedMsg.c_str(),
                        signedMsg.length(), frame, sizeof(frame));
  if (n < 0) { logUsb("MQTT_ENCRYPT_FAILED"); return false; }
  return mqtt.publish(topic.c_str(), frame, retained);
}
static void mqttEvent(const String &event) {
  logUsb(event);
  if (mqtt.connected() && !mqttEventsTopic.isEmpty()) {
    String signedEvent = signedMqttMessage(event);
    mqttSend(mqttEventsTopic, signedEvent);
  }
}
static void mqttCommand(const String &line) {
  // Mensajes MQTT requieren HMAC-SHA256 y secuencia creciente.
  // Formatos: CMD;<seq>;<op>;<mac> y ACK;<id>;<mac>.
  if (line.startsWith("ACK;")) {
    int split = line.indexOf(';', 4);
    if (split < 0) { mqttEvent("ERR;reason=BAD_ACK"); return; }
    String idStr = line.substring(4, split);
    String mac = line.substring(split + 1); mac.toLowerCase();
    if (!isDigits(idStr) || !isHex64(mac) ||
        !constantTimeEquals(mac, hmacSha256(mqttNonce + "|ACK|" + idStr))) {
      mqttEvent("ERR;reason=BAD_ACK_MAC"); return;
    }
    unsigned long long parsed = strtoull(idStr.c_str(), nullptr, 10);
    if (parsed == 0 || parsed > UINT32_MAX) {
      mqttEvent("ERR;reason=BAD_ACK_ID"); return;
    }
    if (confirmOldest((uint32_t)parsed)) {
      mqttEvent("ACK_OK;id=" + idStr + ";queued=" + String(count));
      lastMqttResend = 0;
    } else mqttEvent("ERR;reason=UNEXPECTED_ACK");
    return;
  }
  if (!line.startsWith("CMD;")) { mqttEvent("ERR;reason=BAD_FRAME"); return; }
  int sep = line.indexOf(';', 4);
  int macSep = sep < 0 ? -1 : line.indexOf(';', sep + 1);
  if (sep < 0 || macSep < 0) { mqttEvent("ERR;reason=BAD_FRAME"); return; }
  String seqStr = line.substring(4, sep);
  String op = line.substring(sep + 1, macSep);
  String mac = line.substring(macSep + 1); mac.toLowerCase();
  if (!isDigits(seqStr) || op.isEmpty() || !isHex64(mac)) {
    mqttEvent("ERR;reason=BAD_FRAME"); return;
  }
  unsigned long long parsed = strtoull(seqStr.c_str(), nullptr, 10);
  if (parsed == 0 || parsed > UINT32_MAX || parsed <= mqttLastSequence) {
    mqttEvent("ERR;reason=REPLAY_OR_ORDER"); return;
  }
  if (!constantTimeEquals(mac, hmacSha256(mqttNonce + "|" + seqStr + "|" + op))) {
    mqttEvent("ERR;reason=BAD_COMMAND_MAC"); return;
  }
  mqttLastSequence = (uint32_t)parsed;
  // El protocolo remoto no permite ejecutar ACK por el canal de comandos.
  if (op == "ARM" || op == "DISARM" || op == "SILENCE" || op == "RESETALARM") {
    performCommand(op, false, mqttLastSequence);
    mqttEvent("OK;seq=" + seqStr + ";op=" + op);
  } else if (op == "STATUS") {
    String info = "STATUS;seq=" + seqStr + ";armed=" + String(armed ? 1 : 0) +
      ";alarm=" + String(currentAlarm ? 1 : 0) + ";queued=" + String(count) +
      ";lost=" + String(overflowCount) +
      ";dht_fault=" + String(dhtFault ? 1 : 0);
    mqttEvent(info);
  } else if (op == "GETQUEUE") {
    if (count) {
      String signedPending = signedMqttMessage(readingMessage(pending[head]));
      mqttSend(mqttPendingTopic, signedPending);
    }
    mqttEvent("QUEUE;queued=" + String(count) + ";oldest=" +
              (count ? String(pending[head].id) : String(0)));
  } else mqttEvent("ERR;reason=UNKNOWN_COMMAND;op=" + op);
}
static void mqttCallback(char *topic, byte *payload, unsigned int len) {
  if (String(topic) != mqttCommandsTopic) return;
  if (len == 0 || len > 400) { mqttEvent("ERR;reason=INVALID_INPUT"); return; }
  // Solo se aceptan comandos cifrados con AES-256-GCM (una etiqueta invalida se descarta sin responder)
  uint8_t plain[MAX_LINE + 1];
  int n = cifradoAbrir(APP_SHARED_SECRET, topic, (const char *)payload, len, plain, MAX_LINE);
  if (n <= 0) { logUsb("MQTT_DECRYPT_FAILED"); return; }
  String message;
  message.reserve(n + 1);
  for (int i = 0; i < n; ++i) {
    if (plain[i] < 32 || plain[i] > 126) {
      mqttEvent("ERR;reason=INVALID_INPUT"); return;
    }
    message += (char)plain[i];
  }
  mqttCommand(message);
}
static void initMqtt() {
  mqttNonce = String(nonce);
  // ID estable por placa: Android puede reencontrar el mismo topic tras reiniciar.
  // El nonce HMAC se renueva en cada arranque.
  char id[17];
  snprintf(id, sizeof(id), "%016llx", (unsigned long long)ESP.getEfuseMac());
  mqttBase = String("ti3042/wokwi/") + id;
  mqttTelemetryTopic = mqttBase + "/telemetry";
  mqttPendingTopic = mqttBase + "/pending";
  mqttCommandsTopic = mqttBase + "/commands";
  mqttEventsTopic = mqttBase + "/events";
  mqttPresenceTopic = mqttBase + "/presence";
  netClient.setCACert(MQTT_ROOT_CA);
  netClient.setHandshakeTimeout(15);
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  mqtt.setBufferSize(768);
  mqtt.setSocketTimeout(2);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(energiaModelo.eco); // Ahorro de energía: modem-sleep entre balizas del router (perfil ECO)
  WiFi.begin(WIFI_SSID, WIFI_PASS, WIFI_CANAL);
  lastWifiTry = millis();
  logUsb("WIFI_CONNECTING;ssid=" + String(WIFI_SSID));
  logUsb("MQTT_BASE;topic=" + mqttBase);
  logUsb("MQTT_NONCE;value=" + mqttNonce);
  logUsb("MQTT_INFO;broker=broker.hivemq.com;port=8883;tls=1;ca=AMAZON_OR_ISRG;demo=1");
}
static void updateMqtt() {
  uint32_t now = millis();
  if (netTestUntil != 0) {
    if ((int32_t)(now - netTestUntil) < 0) return;
    netTestUntil = 0;
    logUsb("NETTEST_RECOVERY_STARTED");
  }
  if (WiFi.status() != WL_CONNECTED) {
    lastWiFiState = 0;
    if (previousMqttConnected) {
      previousMqttConnected = false;
      logUsb("MQTT_DISCONNECTED");
    }
    if ((uint32_t)(now - lastWifiTry) >= WIFI_RETRY_MS) {
      logUsb("WIFI_RETRY");
      WiFi.disconnect();
      WiFi.begin(WIFI_SSID, WIFI_PASS, WIFI_CANAL);
      lastWifiTry = now;
    }
    return;
  }
  if (!lastWiFiState) {
    lastWiFiState = 1;
    logUsb("WIFI_CONNECTED;ip=" + WiFi.localIP().toString());
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    logUsb("TLS_TIME_SYNC_STARTED");
  }
  // La validacion TLS necesita reloj correcto para comprobar la vigencia del certificado.
  if (time(nullptr) < 1700000000) {
    static uint32_t lastTimeNotice = 0;
    if (now - lastTimeNotice >= 10000) {
      logUsb("TLS_WAIT_TIME;ntp=not_ready");
      lastTimeNotice = now;
    }
    return;
  }
  if (!mqtt.connected()) {
    if (previousMqttConnected) {
      previousMqttConnected = false;
      logUsb("MQTT_DISCONNECTED");
    }
    if (lastMqttTry && (uint32_t)(now - lastMqttTry) < MQTT_RETRY_MS) return;
    lastMqttTry = now;
    String clientId = "wokwi-" + mqttBase.substring(mqttBase.lastIndexOf('/') + 1);
    Serial.printf("TLS_CLOCK;unix=%lld\n", (long long)time(nullptr));
    if (mqtt.connect(clientId.c_str())) {
      previousMqttConnected = true;
      logUsb("MQTT_TLS_CONNECTED;verification=ENABLED;trust_anchors=AMAZON_ISRG;port=8883");
      bool subscribed = mqtt.subscribe(mqttCommandsTopic.c_str());
      mqttEvent("MQTT_CONNECTED;subscribed=" + String(subscribed ? 1 : 0));
      logUsb("MQTT_TOPICS;telemetry=" + mqttTelemetryTopic + ";pending=" + mqttPendingTopic +
             ";commands=" + mqttCommandsTopic + ";events=" + mqttEventsTopic +
             ";presence=" + mqttPresenceTopic);
      mqttEvent("CHALLENGE;nonce=" + mqttNonce);
      // Nonce publico (no es la clave). Retenido para Android que llega tarde.
      String presence = signedMqttMessage("CHALLENGE;nonce=" + mqttNonce);
      mqttSend(mqttPresenceTopic, presence, true);
      lastMqttResend = 0;
    } else logUsb("MQTT_RETRY;state=" + String(mqtt.state()));
    return;
  }
  mqtt.loop();
  if (count && (lastMqttResend == 0 || (uint32_t)(now - lastMqttResend) >= MQTT_RESEND_MS)) {
    String signedPending = signedMqttMessage(readingMessage(pending[head]));
    bool sent = mqttSend(mqttPendingTopic, signedPending);
    if (sent) {
      logUsb("MQTT_PENDING_SENT;id=" + String(pending[head].id) + ";queued=" + String(count));
      lastMqttResend = now;
    }
  }
}
static void publishLatestMqtt() {
  if (!mqtt.connected()) return;
  String signedTelemetry = signedMqttMessage(readingMessage(latest));
  bool sent = mqttSend(mqttTelemetryTopic, signedTelemetry);
  if (sent) logUsb("MQTT_PUBLISHED;id=" + String(latest.id));
  else logUsb("MQTT_PUBLISH_FAILED;id=" + String(latest.id));
}
#endif

static void logEnergia();
static void setEco(bool eco);
#ifdef WOKWI_SIMULATION
static void onUsbLine(const String &line);
// Autodiagnostico (comando SELFTEST): ejecuta de una vez cifrado, firma, rechazo de comandos invalidos,
// cola en memoria no volatil y estimacion de energia, e imprime OK/FALLO de cada prueba.
static void runSelfTest() {
  int ok = 0, total = 0;
  auto resultado = [&](const char *nombre, bool pasa) {
    total++;
    if (pasa) ok++;
    logUsb(String("SELFTEST;") + nombre + "=" + (pasa ? "OK" : "FALLO"));
  };
  logUsb("SELFTEST_START");
  const char *aad = "ti3042/selftest/telemetry";
  const char *msg = "DATA;id=1;temp=24.0;hum=40.0;gas=1000;armed=1;alarm=0";
  char frame[400];
  uint8_t plain[160];
  int n = cifradoSellar(APP_SHARED_SECRET, aad, (const uint8_t *)msg, strlen(msg), frame, sizeof(frame));
  int m = n > 0 ? cifradoAbrir(APP_SHARED_SECRET, aad, frame, n, plain, sizeof(plain)) : -1;
  resultado("AES256GCM_cifrar_y_descifrar", m == (int)strlen(msg) && memcmp(plain, msg, m) == 0);
  resultado("AES256GCM_otro_tema_rechazado",
            n > 0 && cifradoAbrir(APP_SHARED_SECRET, "ti3042/otro/tema", frame, n, plain, sizeof(plain)) < 0);
  if (n > 0) frame[n / 2] = (frame[n / 2] == 'A') ? 'B' : 'A';
  resultado("AES256GCM_mensaje_alterado_rechazado",
            n > 0 && cifradoAbrir(APP_SHARED_SECRET, aad, frame, n, plain, sizeof(plain)) < 0);
  String firma = hmacSha256(mqttNonce + "|prueba");
  String falsa = firma;
  falsa.setCharAt(0, falsa[0] == '0' ? '1' : '0');
  resultado("HMAC_firma_valida_y_alterada",
            constantTimeEquals(firma, hmacSha256(mqttNonce + "|prueba")) && !constantTimeEquals(falsa, firma));
  String ceros;
  for (int i = 0; i < 64; i++) ceros += '0';
  mqttCommand("CMD;" + String(mqttLastSequence + 1) + ";ARM;" + ceros);
  resultado("Comando_con_firma_falsa_rechazado", ultimoUsb.startsWith("ERR;reason=BAD_COMMAND_MAC"));
  mqttCommand("CMD;" + String(mqttLastSequence) + ";ARM;" + ceros);
  resultado("Comando_repetido_replay_rechazado", ultimoUsb.startsWith("ERR;reason=REPLAY_OR_ORDER"));
  onUsbLine("STORETEST");
  resultado("Cola_NVS_guardar_y_restaurar", ultimoUsb.startsWith("STORETEST_OK"));
  resultado("Perfil_energia_ECO_activo", energiaModelo.eco && getCpuFrequencyMhz() == 80);
  logUsb("SELFTEST_RESULTADO;ok=" + String(ok) + ";total=" + String(total) + ";queued=" + String(count));
  logEnergia();
}
#endif
static void onUsbLine(const String &line) {
#ifdef WOKWI_SIMULATION
  if (line == "SELFTEST") { runSelfTest(); return; }
  if (line == "ENERGY") { logEnergia(); return; }
  if (line == "ECO") { setEco(true); return; }
  if (line == "NOECO") { setEco(false); return; }
  if (line == "STORETEST") {
    // Prueba NVS en Wokwi: simula perdida de RAM SIN reinicio de CPU.
    // ESP.restart() ocasiono watchdog en versiones del simulador.
    // No afirmar que esta prueba equivale a un reinicio fisico.
    if (!saveQueue()) { logUsb("STORETEST_FAILED;reason=SAVE_FAILED"); return; }
    const size_t expectedCount = count;
    const uint32_t expectedNext = nextId;
    memset(pending, 0, sizeof(pending));
    head = 0;
    count = 0;
    nextId = 1;
    overflowCount = 0;
    logUsb("STORETEST_RAM_CLEARED");
    restoreQueue();
    if (count == expectedCount && nextId == expectedNext)
      logUsb("STORETEST_OK;restored=" + String(count) + ";next=" + String(nextId));
    else logUsb("STORETEST_FAILED;expected=" + String(expectedCount) + ";actual=" + String(count));
    return;
  }
  if (line == "NETTEST") {
    // Solo diagnostico Wokwi: desconecta Wi-Fi/MQTT durante 12 segundos
    // sin detener las lecturas ni las alarmas.
    mqtt.disconnect();
    WiFi.disconnect();
    previousMqttConnected = false;
    lastWiFiState = 0;
    netTestUntil = millis() + 12000;
    lastWifiTry = millis() - WIFI_RETRY_MS;
    lastMqttTry = 0;
    logUsb("NETTEST_STARTED;offline_ms=12000");
    return;
  }
#endif
  // Diagnostico SOLO por USB/Wokwi: NO es un ACK remoto seguro.
  if (line.startsWith("ACK:")) {
    String idText = line.substring(4);
    if (!isDigits(idText)) { logUsb("ERR;reason=BAD_ACK_ID"); return; }
    unsigned long long parsed = strtoull(idText.c_str(), nullptr, 10);
    if (parsed == 0 || parsed > UINT32_MAX) {
      logUsb("ERR;reason=BAD_ACK_ID"); return;
    }
    uint32_t id = (uint32_t)parsed;
    if (confirmOldest(id)) {
      logUsb("ACK_OK;id=" + String(id) + ";queued=" + String(count));
    } else {
      logUsb("ERR;reason=UNEXPECTED_ACK");
    }
    return;
  }
  // Comandos locales para desarrollo; USB no ofrece autenticacion ni cifrado.
  performCommand(line, false, 0);
}
static void consume(Stream &stream, char *buffer, size_t &used, bool remote) {
  while (stream.available()) {
    char c = (char)stream.read();
    if (c == '\r') continue;
    if (c == '\n') {
      buffer[used] = '\0';
      if (used) {
        if (remote) onBluetoothLine(String(buffer)); else onUsbLine(String(buffer));
      }
      used = 0;
    } else if ((unsigned char)c < 32 || (unsigned char)c > 126 || used >= MAX_LINE) {
      used = 0;
      if (remote) sendBt("ERR;reason=INVALID_INPUT");
    } else buffer[used++] = c;
  }
}
static void sampleSensors() {
  float temp = dht.readTemperature();
  float hum = dht.readHumidity();
  bool valid = !isnan(temp) && !isnan(hum);
  int gas = analogRead(PIN_GAS);
  if (valid) {
    dhtConsecutiveFailures = 0;
    dhtFault = false;
    lastValidTempHigh = (temp >= 50.0f);
  } else {
    if (dhtConsecutiveFailures < 255) ++dhtConsecutiveFailures;
    dhtFault = dhtConsecutiveFailures >= 2;
  }
  bool alert = armed && (lastValidTempHigh || gas >= 2000 || dhtFault);
  if (alert && !currentAlarm) silenced = false; // una nueva alarma anula silencio anterior
  currentAlarm = alert;
  latest = {nextId++, temp, hum, gas, valid, armed, alert};
  appendReading(latest);
#ifdef WOKWI_SIMULATION
  if (++samplesSinceSave >= PERSIST_EVERY_SAMPLES) saveQueue();
#endif
  logUsb(readingMessage(latest));
#ifdef WOKWI_SIMULATION
  publishLatestMqtt();
#endif
  if (!valid) logUsb("WARN;reason=DHT_READ_FAILED;streak=" + String(dhtConsecutiveFailures));
  if (dhtFault) logUsb("WARN;reason=DHT_SENSOR_FAULT;alarm=" + String(currentAlarm ? 1 : 0));
}
static void updateOutputs() {
  uint32_t now = millis();
  bool led = currentAlarm && ((now / 300) % 2 == 0);
  bool buzzer = currentAlarm && !silenced && (now % 1000 < 250);
  digitalWrite(PIN_LED, led ? HIGH : LOW);
  digitalWrite(PIN_BUZZER, buzzer ? HIGH : LOW);
  energiaModelo.actualizar(now, led, buzzer);
}
// Muestra la estimación de consumo en el monitor serial (comando ENERGY y cada 60 s)
static void logEnergia() {
  char texto[700];
  energiaModelo.resumen(texto, sizeof(texto), (int)getCpuFrequencyMhz());
  logUsb(String(texto));
}
// Activa o desactiva las optimizaciones de energía para poder compararlas en vivo
static void setEco(bool eco) {
  energiaModelo.fijarPerfil(millis(), eco);
  setCpuFrequencyMhz(eco ? 80 : 240);
  WiFi.setSleep(eco);
  logUsb(String("ECO_MODE;perfil=") + (eco ? "ECO" : "SIN_OPTIMIZAR") +
         ";cpu_mhz=" + String((int)getCpuFrequencyMhz()) + ";wifi_sleep=" + String(eco ? 1 : 0));
  logEnergia();
}
static void updateConnection() {
#ifdef WOKWI_SIMULATION
  return; // Wokwi no simula Bluetooth Classic SPP; evitar iniciar su controlador.
#else
  bool connected = bt.hasClient();
  if (connected != wasConnected) {
    wasConnected = connected;
    resetSession();
    logUsb(connected ? "INFO;bt=CONNECTED" : "INFO;bt=DISCONNECTED");
  }
  if (!connected) return;
  if (!authorized && (uint32_t)(millis() - connectionStart) > AUTH_TIMEOUT_MS) {
    sendBt("ERR;reason=AUTH_TIMEOUT"); bt.disconnect(); return;
  }
  if (authorized && (uint32_t)(millis() - lastActive) > IDLE_TIMEOUT_MS) {
    sendBt("ERR;reason=SESSION_TIMEOUT"); bt.disconnect(); return;
  }
  if (!authorized || count == 0) return;
  if (lastTransmit == 0 || (uint32_t)(millis() - lastTransmit) >= RETRY_MS) {
    sendBt(readingMessage(pending[head]));
    lastTransmit = millis();
  }
#endif
}
void setup() {
  // Ahorro de energía: CPU a 80 MHz (mínimo que admite WiFi) antes de abrir el puerto serial
  setCpuFrequencyMhz(80);
  Serial.begin(115200);
  delay(2000);
  Serial.println("DEBUG: SETUP INICIADO");
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_LED, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  dht.begin();
  analogReadResolution(12);
  if (strlen(APP_SHARED_SECRET) < 24 || strcmp(APP_SHARED_SECRET, "REPLACE_WITH_32_RANDOM_CHARACTERS") == 0) {
    Serial.println("FATAL;reason=SET_SECRET_IN_INCLUDE_SECRETS_H");
    while (true) { digitalWrite(PIN_LED, !digitalRead(PIN_LED)); delay(500); }
  }
#ifdef WOKWI_SIMULATION
  logUsb("BOOT;mode=WOKWI;bt=DISABLED;mqtt=ENABLED");
  makeNonce();
  restoreQueue();
  initMqtt();
#else
  bool started = bt.begin("ESP32-Alerta-IoT");
  logUsb(started ? "BOOT;bt=READY" : "WARN;bt=UNAVAILABLE_USB_ONLY");
#endif
  logUsb("BOOT;commands=HELP,STATUS,GETQUEUE,ACK:<id>,ARM,DISARM,SILENCE,RESETALARM,NETTEST,STORETEST,ENERGY,ECO,NOECO,SELFTEST");
  sampleSensors();
  lastSample = millis();
}
void loop() {
  consume(Serial, usbLine, usbLen, false);
#ifndef WOKWI_SIMULATION
  if (bt.hasClient()) consume(bt, btLine, btLen, true);
#endif
  if ((uint32_t)(millis() - lastSample) >= SAMPLE_MS) {
    lastSample = millis();
    sampleSensors();
  }
  updateOutputs();
  static uint32_t lastEnergyLog = 0;
  if ((uint32_t)(millis() - lastEnergyLog) >= 60000) { lastEnergyLog = millis(); logEnergia(); }
  updateConnection();
#ifdef WOKWI_SIMULATION
  updateMqtt();
#endif
  delay(10);
}
