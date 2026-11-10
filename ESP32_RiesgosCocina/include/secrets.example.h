#pragma once
// Solo para desarrollo. Generar un secreto aleatorio por dispositivo.
// Ejemplo: python -c "import secrets; print(secrets.token_hex(32))"
// La aplicacion Android debe poseer el MISMO secreto por un canal seguro.
#define APP_SHARED_SECRET "REPLACE_WITH_32_RANDOM_CHARACTERS"
