<?php
/**
 * POST /api/login.php  (botón INGRESAR)
 * Campos: correo, password
 * Compara la contraseña con el hash guardado.
 */
require_once __DIR__ . '/comun.php';

[$correo, $password] = leerCredenciales();
$pdo = db();

$stmt = $pdo->prepare('SELECT id, password_hash FROM usuarios WHERE correo = :correo LIMIT 1');
$stmt->execute([':correo' => $correo]);
$usuario = $stmt->fetch();

// Mismo mensaje si no existe o si la clave es incorrecta (no revela qué correos existen)
if (!$usuario || !password_verify($password, $usuario['password_hash'])) {
    responder(401, false, 'Correo o contraseña incorrectos');
}

$pdo->prepare('UPDATE usuarios SET ultimo_acceso = NOW() WHERE id = :id')
    ->execute([':id' => $usuario['id']]);

responder(200, true, 'Bienvenido', ['correo' => $correo]);
