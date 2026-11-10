// Cifrado de los mensajes MQTT: AES-256-GCM (confidencialidad + autenticidad).
// Formato:  "E1;" + Base64( IV[12] | texto cifrado | etiqueta GCM[16] )
// Clave:    HMAC-SHA256(secreto compartido, "riesgos-aes-key-v1")  (32 bytes)
// AAD:      el nombre completo del tema MQTT (un mensaje no sirve en otro tema)
// El broker publico solo ve bytes ilegibles; la app Android usa el mismo formato (Protocolo.kt).
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <mbedtls/base64.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#ifndef CIFRADO_RANDOM
#include <esp_system.h>
#define CIFRADO_RANDOM(buf, n) esp_fill_random((buf), (n))
#endif

static const size_t CIFRADO_IV = 12;
static const size_t CIFRADO_TAG = 16;

static inline bool cifradoClave(const char *secreto, uint8_t clave[32]) {
  const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  const char *contexto = "riesgos-aes-key-v1";
  if (!info) return false;
  return mbedtls_md_hmac(info, (const uint8_t *)secreto, strlen(secreto),
                         (const uint8_t *)contexto, strlen(contexto), clave) == 0;
}

// Cifra "plano" y escribe la trama en "salida" (texto terminado en 0). Devuelve la longitud o -1.
static inline int cifradoSellar(const char *secreto, const char *aad, const uint8_t *plano, size_t n,
                                char *salida, size_t capacidad) {
  uint8_t clave[32];
  if (!cifradoClave(secreto, clave)) return -1;
  size_t total = CIFRADO_IV + n + CIFRADO_TAG;
  uint8_t *tmp = (uint8_t *)malloc(total);
  if (!tmp) return -1;
  CIFRADO_RANDOM(tmp, CIFRADO_IV);
  mbedtls_gcm_context g;
  mbedtls_gcm_init(&g);
  int rc = mbedtls_gcm_setkey(&g, MBEDTLS_CIPHER_ID_AES, clave, 256);
  if (rc == 0) {
    rc = mbedtls_gcm_crypt_and_tag(&g, MBEDTLS_GCM_ENCRYPT, n, tmp, CIFRADO_IV,
                                   (const uint8_t *)aad, strlen(aad), plano, tmp + CIFRADO_IV,
                                   CIFRADO_TAG, tmp + CIFRADO_IV + n);
  }
  mbedtls_gcm_free(&g);
  int largo = -1;
  if (rc == 0 && capacidad > 4) {
    memcpy(salida, "E1;", 3);
    size_t escritos = 0;
    if (mbedtls_base64_encode((unsigned char *)salida + 3, capacidad - 4, &escritos, tmp, total) == 0) {
      salida[3 + escritos] = '\0';
      largo = (int)(3 + escritos);
    }
  }
  memset(clave, 0, sizeof(clave));
  free(tmp);
  return largo;
}

// Descifra una trama "E1;..." y verifica la etiqueta. Devuelve la longitud del texto plano o -1.
static inline int cifradoAbrir(const char *secreto, const char *aad, const char *trama, size_t largoTrama,
                               uint8_t *plano, size_t capacidad) {
  if (largoTrama < 4 || memcmp(trama, "E1;", 3) != 0) return -1;
  uint8_t clave[32];
  if (!cifradoClave(secreto, clave)) return -1;
  size_t maxBin = largoTrama;  // la decodificacion siempre es menor que la entrada
  uint8_t *bin = (uint8_t *)malloc(maxBin);
  if (!bin) return -1;
  size_t binLen = 0;
  int resultado = -1;
  if (mbedtls_base64_decode(bin, maxBin, &binLen, (const unsigned char *)trama + 3, largoTrama - 3) == 0 &&
      binLen >= CIFRADO_IV + CIFRADO_TAG) {
    size_t n = binLen - CIFRADO_IV - CIFRADO_TAG;
    if (n <= capacidad) {
      mbedtls_gcm_context g;
      mbedtls_gcm_init(&g);
      if (mbedtls_gcm_setkey(&g, MBEDTLS_CIPHER_ID_AES, clave, 256) == 0 &&
          mbedtls_gcm_auth_decrypt(&g, n, bin, CIFRADO_IV, (const uint8_t *)aad, strlen(aad),
                                   bin + CIFRADO_IV + n, CIFRADO_TAG, bin + CIFRADO_IV, plano) == 0) {
        resultado = (int)n;
      }
      mbedtls_gcm_free(&g);
    }
  }
  memset(clave, 0, sizeof(clave));
  free(bin);
  return resultado;
}
