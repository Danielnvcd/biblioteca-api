-- ============================================================================
-- V2: Seed default categories.
--
-- Moved from DataInitializer.seedCategories(). Each INSERT is guarded by
-- NOT EXISTS so this script is safe to run against databases that already
-- contain some of these rows (production, current dev). Future categories
-- added in later migrations follow the same pattern.
--
-- The admin user remains in DataInitializer because its password must be
-- hashed at runtime via the Spring PasswordEncoder.
-- ============================================================================

-- Almacenes
INSERT INTO categories (name, type, sort_order)
SELECT v.name, 'almacenes', v.sort_order
FROM (VALUES
    ('Información general',     0),
    ('Avisos Importantes',      1),
    ('Doc. de operación',       2),
    ('Instructivos',            3),
    ('Estadísticas semanales',  4),
    ('Méritos y resultados',    5),
    ('MSDS',                    6),
    ('Lectura',                 7)
) AS v(name, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM categories c WHERE c.name = v.name AND c.type = 'almacenes'
);

-- Manuales
INSERT INTO categories (name, type, sort_order)
SELECT v.name, 'manuales', v.sort_order
FROM (VALUES
    ('Almacén',                  0),
    ('Ventas',                   1),
    ('Producción INY',           2),
    ('Producción',               3),
    ('Compras',                  4),
    ('Producción PEAD',          5),
    ('Administrativos',          6),
    ('Logística',                7),
    ('General',                  8),
    ('Aseguramiento de calidad', 9),
    ('Producción PET',          10)
) AS v(name, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM categories c WHERE c.name = v.name AND c.type = 'manuales'
);

-- Documentos
INSERT INTO categories (name, type, sort_order)
SELECT v.name, 'documentos', v.sort_order
FROM (VALUES
    ('General',                  0),
    ('Administrativos',          1),
    ('Almacén',                  2),
    ('Logística',                3),
    ('Aseguramiento de calidad', 4),
    ('Compras',                  5),
    ('Producción',               6),
    ('Ventas',                   7),
    ('Producción INY',           8),
    ('Producción PET',           9),
    ('Producción PEAD',         10)
) AS v(name, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM categories c WHERE c.name = v.name AND c.type = 'documentos'
);
