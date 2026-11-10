#!/bin/bash
# Instala la API completa con HTTPS en una sola orden (Raspbian live: hay que repetirlo si la VM se apaga)
set -e
cd "$(dirname "$0")"
bash ./instalar_api.sh
bash ./instalar_https.sh
