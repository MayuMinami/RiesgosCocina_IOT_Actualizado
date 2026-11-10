<?php
/**
 * POST /api/registrar.php  (botón GUARDAR)
 * Campos: correo, password
 * Guarda el usuario con la contraseña cifrada (bcrypt).
 */
require_once __DIR__ . '/comun.php';

[$correo, $password] = leerCredenciales();
$pdo = db();

$hash = password_hash($password, PASSWORD_BCRYPT);

try {
    $stmt = $pdo->prepare('INSERT INTO usuarios (correo, password_hash) VALUES (:correo, :hash)');
    $stmt->execute([':correo' => $correo, ':hash' => $hash]);
    responder(201, true, 'Usuario registrado correctamente');
} catch (PDOException $e) {
    if ($e->getCode() === '23000') {   // correo duplicado (índice UNIQUE)
        responder(409, false, 'Ese correo ya está registrado');
    }
    error_log('Error registrar: ' . $e->getMessage());
    responder(500, false, 'Error al registrar el usuario');
}
