<?php
/**
 * Configuración de conexión a MySQL (AWS RDS).
 * Este archivo va FUERA de la carpeta pública: /var/www/config/db.php
 * así nadie puede descargarlo desde el navegador.
 */

define('DB_HOST', 'TU_ENDPOINT.us-east-1.rds.amazonaws.com');
define('DB_PORT', 3306);
define('DB_NAME', 'riesgos_cocina');
define('DB_USER', 'admin');
define('DB_PASS', 'TU_CLAVE_RDS');

// Certificado de AWS para cifrar (TLS) la conexión Raspbian -> RDS
define('DB_SSL_CA', '/var/www/config/rds-ca.pem');

function conectar(): PDO
{
    $dsn = 'mysql:host=' . DB_HOST . ';port=' . DB_PORT . ';dbname=' . DB_NAME . ';charset=utf8mb4';
    return new PDO($dsn, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE                      => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE           => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES             => false,
        PDO::ATTR_TIMEOUT                      => 5,
        PDO::MYSQL_ATTR_SSL_CA                 => DB_SSL_CA,
        PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT => true,
    ]);
}
