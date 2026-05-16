-- ============================================================================
-- V1: Baseline schema for biblioteca-api.
--
-- This script reproduces the schema that Hibernate previously generated via
-- ddl-auto=update. For databases that already contain data (production, dev
-- with existing volumes), Flyway is configured with baseline-on-migrate=true,
-- so this script will NOT be executed against them — it is only used to bring
-- fresh databases (CI, new dev setups) to the current state.
--
-- Any future schema change must be added as a new V{n}__description.sql file.
-- Never edit this file once it has been applied anywhere.
-- ============================================================================

CREATE TABLE users (
    id                  SERIAL PRIMARY KEY,
    username            VARCHAR(80)  NOT NULL UNIQUE,
    password_hash       VARCHAR(200) NOT NULL,
    role                VARCHAR(20)  DEFAULT 'user',
    totp_secret         VARCHAR(32),
    full_name           VARCHAR(150),
    area                VARCHAR(100),
    position            VARCHAR(100),
    factory             VARCHAR(100),
    contact_info        VARCHAR(200),
    profile_pic         VARCHAR(255) DEFAULT 'default.png',
    last_seen           TIMESTAMP,
    password_changed_at TIMESTAMP
);

CREATE TABLE content (
    id              VARCHAR(36)  PRIMARY KEY,
    title           VARCHAR(150) NOT NULL,
    description     TEXT,
    filename        VARCHAR(255) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    manual_category VARCHAR(50),
    author          VARCHAR(80)  NOT NULL,
    created_at      TIMESTAMP,
    cover_image     VARCHAR(255),
    link_url        TEXT
);

CREATE INDEX idx_content_category        ON content (category);
CREATE INDEX idx_content_manual_category ON content (manual_category);
CREATE INDEX idx_content_created_at      ON content (created_at);
CREATE INDEX idx_content_cat_date        ON content (category, created_at);

CREATE TABLE comments (
    id         SERIAL PRIMARY KEY,
    content_id VARCHAR(36) NOT NULL REFERENCES content(id),
    user_name  VARCHAR(80) NOT NULL,
    text       TEXT NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE categories (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    type       VARCHAR(50)  NOT NULL,
    sort_order INTEGER      DEFAULT 0
);

CREATE TABLE quejas (
    id              SERIAL PRIMARY KEY,
    folio           VARCHAR(20)  NOT NULL UNIQUE,
    fecha           DATE         NOT NULL,
    cliente         VARCHAR(100) NOT NULL,
    motivo          VARCHAR(200) NOT NULL,
    solucion        TEXT,
    estado          VARCHAR(20)  DEFAULT 'Nueva',
    solucion_imagen VARCHAR(255),
    created_at      TIMESTAMP
);

CREATE TABLE seguridad (
    id          SERIAL PRIMARY KEY,
    titulo      VARCHAR(100) NOT NULL,
    descripcion TEXT         NOT NULL,
    nivel       VARCHAR(20)  DEFAULT 'info',
    fecha       DATE,
    created_at  TIMESTAMP
);

CREATE TABLE system_config (
    key   VARCHAR(50) PRIMARY KEY,
    value VARCHAR(200)
);

CREATE TABLE corrective_action (
    id            SERIAL PRIMARY KEY,
    folio         VARCHAR(50)  NOT NULL,
    fecha_reporte DATE,
    origen        VARCHAR(100) NOT NULL,
    reporta       VARCHAR(100) NOT NULL,
    descripcion   TEXT         NOT NULL,
    medida_tomada VARCHAR(200) DEFAULT 'Acción correctiva',
    departamento  VARCHAR(100),
    fecha_eval_1  DATE,
    fecha_eval_2  DATE,
    estatus       VARCHAR(50)  DEFAULT 'Pendiente'
);

CREATE TABLE correction_activity (
    id               SERIAL PRIMARY KEY,
    action_id        INTEGER      NOT NULL REFERENCES corrective_action(id),
    descripcion      TEXT         NOT NULL,
    responsable      VARCHAR(100) NOT NULL,
    fecha_compromiso DATE         NOT NULL,
    estatus          VARCHAR(50)  DEFAULT 'Pendiente',
    observaciones    TEXT
);

CREATE TABLE questionnaires (
    id          SERIAL PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    questions   TEXT NOT NULL,
    created_at  TIMESTAMP,
    created_by  VARCHAR(80)
);

CREATE TABLE questionnaire_responses (
    id               SERIAL PRIMARY KEY,
    questionnaire_id INTEGER NOT NULL REFERENCES questionnaires(id),
    user_name        VARCHAR(80) NOT NULL,
    answers          TEXT NOT NULL,
    created_at       TIMESTAMP
);

CREATE TABLE kpis (
    id           SERIAL PRIMARY KEY,
    nombre       VARCHAR(100) NOT NULL,
    valor_actual VARCHAR(50)  NOT NULL,
    meta         VARCHAR(50)  NOT NULL,
    unidad       VARCHAR(20)  NOT NULL,
    estado       VARCHAR(20)  DEFAULT 'ok'
);

CREATE TABLE objetivos (
    id                     SERIAL PRIMARY KEY,
    titulo                 VARCHAR(200) NOT NULL,
    proceso_lider          VARCHAR(100) NOT NULL,
    procesos_involucrados  VARCHAR(200),
    meta_desc              VARCHAR(100) NOT NULL,
    resultado_actual       VARCHAR(50)  NOT NULL,
    estado                 VARCHAR(20)  DEFAULT 'en_meta'
);

CREATE TABLE audit_logs (
    id         SERIAL PRIMARY KEY,
    username   VARCHAR(80),
    action     VARCHAR(200),
    ip         VARCHAR(45),
    created_at TIMESTAMP
);
