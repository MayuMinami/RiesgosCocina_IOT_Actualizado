<?php
/**
 * POST /api/eliminar.php  (botón ELIMINAR USUARIO)
 * Campos: correo, password
 * Solo elimina si la contraseña es correcta (control de acceso).
 */
require_once __DIR__ . '/comun.php';

[$correo, $password] = leerCredenciales();
$pdo = db();

$stmt = $pdo->prepare('SELECT id, password_hash FROM usuarios WHERE correo = :correo LIMIT 1');
$stmt->execute([':correo' => $correo]);
$usuario = $stmt->fetch();

if (!$usuario || !password_verify($password, $usuario['password_hash'])) {
    responder(401, false, 'Correo o contraseña incorrectos');
}

$pdo->prepare('DELETE FROM usuarios WHERE id = :id')->execute([':id' => $usuario['id']]);

responder(200, true, 'Usuario eliminado');
