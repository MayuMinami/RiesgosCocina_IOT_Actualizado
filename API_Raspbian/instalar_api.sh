#!/bin/bash
# Instala la API PHP del login en Raspbian (Apache + PHP) conectada a AWS RDS con TLS
set -e
cd "$(dirname "$0")"
sudo sed -i '/security/d' /etc/apt/sources.list || true
sudo apt-get update
sudo apt-get install -y apache2 php libapache2-mod-php php-mysql curl
sudo mkdir -p /var/www/html/api /var/www/config
sudo cp api/*.php /var/www/html/api/
sudo cp config/db.php /var/www/config/db.php
sudo curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /var/www/config/rds-ca.pem
sudo chown -R root:www-data /var/www/config
sudo chmod 750 /var/www/config
sudo chmod 640 /var/www/config/db.php /var/www/config/rds-ca.pem
sudo systemctl enable apache2
sudo systemctl restart apache2
echo "API instalada. IP de esta Raspbian:"
hostname -I
