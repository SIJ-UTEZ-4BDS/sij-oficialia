-- 1. Materias Transversales
CREATE TABLE IF NOT EXISTS materias (
                                        id_materia INT AUTO_INCREMENT PRIMARY KEY,
                                        nombre_materia VARCHAR(80) NOT NULL UNIQUE,
                                        activo TINYINT(1) NOT NULL DEFAULT 1
);

-- 2. Distritos Judiciales
CREATE TABLE IF NOT EXISTS distritos (
                                         id_distrito INT AUTO_INCREMENT PRIMARY KEY,
                                         nombre_distrito VARCHAR(120) NOT NULL UNIQUE,
                                         municipio_cabecera VARCHAR(80) NOT NULL DEFAULT 'Cuernavaca',
                                         id_distrito_cercano INT NULL,
                                         activo TINYINT(1) NOT NULL DEFAULT 1,
                                         CONSTRAINT fk_distrito_cercano FOREIGN KEY (id_distrito_cercano) REFERENCES distritos(id_distrito)
);

-- 3. Juzgados vinculados a Distrito y Materia
CREATE TABLE IF NOT EXISTS juzgados (
                                        id_juzgado INT AUTO_INCREMENT PRIMARY KEY,
                                        numero_juzgado VARCHAR(15) NOT NULL UNIQUE,
                                        nombre_juzgado VARCHAR(120) NOT NULL,
                                        id_distrito INT NOT NULL,
                                        id_materia INT NOT NULL,
                                        activo TINYINT(1) NOT NULL DEFAULT 1,
                                        CONSTRAINT fk_juzgado_distrito FOREIGN KEY (id_distrito) REFERENCES distritos(id_distrito),
                                        CONSTRAINT fk_juzgado_materia FOREIGN KEY (id_materia) REFERENCES materias(id_materia)
);

-- 4. Control de Accesos, Contacto y Seguridad
CREATE TABLE IF NOT EXISTS usuarios (
                                        id_usuario INT AUTO_INCREMENT PRIMARY KEY,
                                        clave_empleado VARCHAR(30) NOT NULL UNIQUE,
                                        nombre_completo VARCHAR(150) NOT NULL,
                                        correo VARCHAR(120) NOT NULL UNIQUE,
                                        telefono_contacto VARCHAR(20) NOT NULL DEFAULT '7770000000',
                                        curp VARCHAR(18) NULL,
                                        cargo_especifico VARCHAR(80) NOT NULL DEFAULT 'Servidor Público Judicial',
                                        modulo_adscripcion VARCHAR(40) NOT NULL DEFAULT 'GLOBAL',
                                        password VARCHAR(255) NOT NULL,
                                        rol VARCHAR(50) NOT NULL,
                                        id_juzgado INT NULL,
                                        intentos_fallidos INT NOT NULL DEFAULT 0,
                                        bloqueado_hasta DATETIME NULL,
                                        codigo_recuperacion VARCHAR(6) NULL,
                                        expira_recuperacion DATETIME NULL,
                                        activo TINYINT(1) NOT NULL DEFAULT 1,
                                        fecha_alta DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        CONSTRAINT fk_usuario_juzgado FOREIGN KEY (id_juzgado) REFERENCES juzgados(id_juzgado)
);

-- 5. Bitácora de Trazabilidad y Auditoría
CREATE TABLE IF NOT EXISTS auditoria_historial (
                                                   id_auditoria INT AUTO_INCREMENT PRIMARY KEY,
                                                   id_referencia INT NULL,
                                                   usuario_responsable VARCHAR(80) NOT NULL,
                                                   modulo_origen VARCHAR(50) NOT NULL,
                                                   campo_modificado VARCHAR(80) NOT NULL,
                                                   valor_anterior VARCHAR(255) NULL,
                                                   valor_nuevo VARCHAR(255) NOT NULL,
                                                   fecha_hora DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Datos Semilla Base (Idempotentes)
INSERT IGNORE INTO materias (id_materia, nombre_materia) VALUES
                                                             (1, 'Familiar'),
                                                             (2, 'Violencia Familiar'),
                                                             (3, 'Civil'),
                                                             (4, 'Penal'),
                                                             (5, 'Mercantil');

INSERT IGNORE INTO distritos (id_distrito, nombre_distrito, municipio_cabecera, id_distrito_cercano) VALUES
                                                                                                         (1, 'Distrito 1 - Cuernavaca Sede Central (Atlacomulco)', 'Cuernavaca', NULL),
                                                                                                         (2, 'Distrito 2 - Xochitepec', 'Xochitepec', 1),
                                                                                                         (3, 'Distrito 1 - Cuernavaca Sede Norte (Chamilpa)', 'Cuernavaca', 1),
                                                                                                         (4, 'Distrito 1 - Cuernavaca Ciudad Judicial', 'Cuernavaca', 1),
                                                                                                         (5, 'Distrito 3 - Temixco', 'Temixco', 1),
                                                                                                         (6, 'Distrito 4 - Cuautla', 'Cuautla', 1);

INSERT IGNORE INTO juzgados (id_juzgado, numero_juzgado, nombre_juzgado, id_distrito, id_materia) VALUES
                                                                                                      (1, 'J01-CVA', 'Juzgado Primero Familiar de Cuernavaca', 1, 1),
                                                                                                      (2, 'J02-XOC', 'Juzgado Segundo Violencia (Xochitepec)', 2, 2),
                                                                                                      (3, 'J03-CVA', 'Juzgado Segundo Civil de Cuernavaca', 3, 3),
                                                                                                      (4, 'J04-CVA', 'Juzgado Tercero Penal de Cuernavaca', 4, 4),
                                                                                                      (5, 'J05-TEM', 'Juzgado Mixto de Primera Instancia (Temixco)', 5, 1);