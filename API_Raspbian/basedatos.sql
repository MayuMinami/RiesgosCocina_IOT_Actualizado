-- Base de datos del login - Sistema de prevención de riesgos en la cocina
-- Ejecutar en MySQL Workbench conectado a AWS RDS

CREATE DATABASE IF NOT EXISTS riesgos_cocina
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE riesgos_cocina;

CREATE TABLE IF NOT EXISTS usuarios (
  id             INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  correo         VARCHAR(100)  NOT NULL,
  password_hash  VARCHAR(255)  NOT NULL,          -- contraseña cifrada con bcrypt, nunca en texto plano
  creado_en      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ultimo_acceso  DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_usuarios_correo (correo)
) ENGINE=InnoDB;
