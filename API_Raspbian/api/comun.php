<?php
/**
 * Funciones comunes de la API: respuestas JSON, validación y conexión.
 */
require_once '/var/www/config/db.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

function responder(int $codigo, bool $ok, string $mensaje, array $extra = []): void
{
    http_response_code($codigo);
    echo json_encode(array_merge(['ok' => $ok, 'mensaje' => $mensaje], $extra), JSON_UNESCAPED_UNICODE);
    exit;
}

// Solo por HTTPS: las credenciales nunca viajan en claro por la red
if (empty($_SERVER['HTTPS']) || $_SERVER['HTTPS'] === 'off') {
    responder(426, false, 'Se requiere HTTPS');
}

// Solo se aceptan peticiones POST (los datos no viajan en la URL)
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    responder(405, false, 'Método no permitido');
}

// Lee los campos enviados por la app (form-urlencoded)
function leerCredenciales(): array
{
    $correo   = strtolower(trim($_POST['correo'] ?? ''));
    $password = $_POST['password'] ?? '';

    if (!filter_var($correo, FILTER_VALIDATE_EMAIL) || strlen($correo) > 100) {
        responder(400, false, 'Correo no válido');
    }
    if (strlen($password) < 8 || strlen($password) > 72) {
        responder(400, false, 'La contraseña debe tener entre 8 y 72 caracteres');
    }
    return [$correo, $password];
}

function db(): PDO
{
    try {
        return conectar();
    } catch (PDOException $e) {
        error_log('Error BD: ' . $e->getMessage());   // el detalle queda en el log del servidor, no se envía a la app
        responder(500, false, 'No se pudo conectar con la base de datos');
    }
}
