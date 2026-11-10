#!/bin/bash
# Activa HTTPS (TLS 1.2/1.3) en la API de login de la Raspbian y desactiva el acceso por HTTP.
# Ejecutar dentro de la VM:  sudo bash /media/*/API_RIESGOS/instalar_https.sh
set -e
cd "$(dirname "$0")"

echo "[1/5] Actualizando los archivos de la API..."
mkdir -p /var/www/html/api
cp api/*.php /var/www/html/api/

echo "[2/5] Instalando el certificado del servidor..."
mkdir -p /etc/ssl/riesgos
cp ssl/servidor.crt /etc/ssl/riesgos/servidor.crt
cp ssl/servidor.key /etc/ssl/riesgos/servidor.key
chown root:root /etc/ssl/riesgos/servidor.crt /etc/ssl/riesgos/servidor.key
chmod 644 /etc/ssl/riesgos/servidor.crt
chmod 600 /etc/ssl/riesgos/servidor.key

echo "[3/5] Configurando Apache..."
a2enmod ssl headers rewrite >/dev/null
cat > /etc/apache2/sites-available/riesgos-ssl.conf <<'CONF'
<VirtualHost *:443>
    ServerName raspbian.local
    DocumentRoot /var/www/html
    SSLEngine on
    SSLCertificateFile    /etc/ssl/riesgos/servidor.crt
    SSLCertificateKeyFile /etc/ssl/riesgos/servidor.key
    SSLProtocol -all +TLSv1.2 +TLSv1.3
    SSLHonorCipherOrder on
    Header always set Strict-Transport-Security "max-age=31536000"
    Header always set X-Content-Type-Options "nosniff"
</VirtualHost>
CONF
cat > /etc/apache2/sites-available/riesgos-http.conf <<'CONF'
# El puerto 80 solo redirige a HTTPS (la API además rechaza cualquier petición sin TLS)
<VirtualHost *:80>
    RewriteEngine On
    RewriteRule ^ https://%{HTTP_HOST}%{REQUEST_URI} [R=301,L]
</VirtualHost>
CONF
a2dissite 000-default >/dev/null 2>&1 || true
a2ensite riesgos-ssl riesgos-http >/dev/null

echo "[4/5] Reiniciando Apache..."
apache2ctl configtest
systemctl restart apache2

echo "[5/5] Comprobando..."
CODIGO=$(curl -sk -o /dev/null -w "%{http_code}" -X POST https://localhost/api/login.php || true)
CODIGO80=$(curl -s -o /dev/null -w "%{http_code}" http://localhost/api/login.php || true)
echo "HTTPS responde: $CODIGO (esperado 400 = API activa)"
echo "HTTP  responde: $CODIGO80 (esperado 301 = redirige a HTTPS)"
echo "IP de esta Raspbian:"
hostname -I
