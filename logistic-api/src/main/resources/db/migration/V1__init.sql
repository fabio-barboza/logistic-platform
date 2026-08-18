-- ============================================================
-- Script de criação de tabelas
-- Banco de dados: PostgreSQL
-- ============================================================

-- ENUMs

CREATE TYPE route_status AS ENUM (
    'COMPLETED',
    'COMPLETED_WITH_FAILURES',
    'CANCELED',
    'IN_PROGRESS'
);

CREATE TYPE order_status AS ENUM (
    'DELIVERED',
    'IN_ROUTE',
    'COLLECTED',
    'CANCELED',
    'DELIVER_FAILURE'
);

-- ============================================================
-- VEHICLE
-- ============================================================

CREATE TABLE vehicle (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    capacity   INTEGER      NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_vehicle PRIMARY KEY (id)
);

COMMENT ON TABLE  vehicle            IS 'Veículos disponíveis na frota';
COMMENT ON COLUMN vehicle.id         IS 'Identificador único do veículo';
COMMENT ON COLUMN vehicle.name       IS 'Nome ou modelo do veículo';
COMMENT ON COLUMN vehicle.capacity   IS 'Capacidade de carga do veículo (em unidades ou kg, conforme regra de negócio)';
COMMENT ON COLUMN vehicle.created_at IS 'Data e hora de criação do registro';
COMMENT ON COLUMN vehicle.updated_at IS 'Data e hora da última atualização do registro';

-- ============================================================
-- DRIVER
-- ============================================================

CREATE TABLE driver (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    name       VARCHAR(150) NOT NULL,
    email      VARCHAR(150) NOT NULL,
    birthday   DATE         NOT NULL,
    city       VARCHAR(100) NOT NULL,
    state      CHAR(2)      NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_driver       PRIMARY KEY (id),
    CONSTRAINT uq_driver_email UNIQUE (email)
);

COMMENT ON TABLE  driver            IS 'Motoristas cadastrados na plataforma';
COMMENT ON COLUMN driver.id         IS 'Identificador único do motorista';
COMMENT ON COLUMN driver.name       IS 'Nome completo do motorista';
COMMENT ON COLUMN driver.email      IS 'Endereço de e-mail do motorista (único)';
COMMENT ON COLUMN driver.birthday   IS 'Data de nascimento do motorista';
COMMENT ON COLUMN driver.city       IS 'Cidade de residência do motorista';
COMMENT ON COLUMN driver.state      IS 'Sigla do estado (UF) de residência do motorista';
COMMENT ON COLUMN driver.created_at IS 'Data e hora de criação do registro';
COMMENT ON COLUMN driver.updated_at IS 'Data e hora da última atualização do registro';

-- ============================================================
-- DRIVER_VEHICLE  (associação motorista ↔ veículo)
-- ============================================================

CREATE TABLE driver_vehicle (
    id         UUID      NOT NULL DEFAULT gen_random_uuid(),
    driver_id  UUID      NOT NULL,
    vehicle_id UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_driver_vehicle            PRIMARY KEY (id),
    CONSTRAINT uq_driver_vehicle            UNIQUE (driver_id, vehicle_id),
    CONSTRAINT fk_driver_vehicle_driver     FOREIGN KEY (driver_id)  REFERENCES driver  (id) ON DELETE CASCADE,
    CONSTRAINT fk_driver_vehicle_vehicle    FOREIGN KEY (vehicle_id) REFERENCES vehicle (id) ON DELETE CASCADE
);

COMMENT ON TABLE  driver_vehicle            IS 'Associação entre motoristas e seus veículos';
COMMENT ON COLUMN driver_vehicle.id         IS 'Identificador único da associação';
COMMENT ON COLUMN driver_vehicle.driver_id  IS 'Referência ao motorista';
COMMENT ON COLUMN driver_vehicle.vehicle_id IS 'Referência ao veículo';
COMMENT ON COLUMN driver_vehicle.created_at IS 'Data e hora em que o veículo foi vinculado ao motorista';

-- ============================================================
-- ROUTE
-- ============================================================

CREATE TABLE route (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    driver_id  UUID         NOT NULL,
    status     route_status NOT NULL DEFAULT 'IN_PROGRESS',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_route        PRIMARY KEY (id),
    CONSTRAINT fk_route_driver FOREIGN KEY (driver_id) REFERENCES driver (id) ON DELETE RESTRICT
);

COMMENT ON TABLE  route            IS 'Rotas de entrega atribuídas aos motoristas';
COMMENT ON COLUMN route.id         IS 'Identificador único da rota';
COMMENT ON COLUMN route.driver_id  IS 'Referência ao motorista responsável pela rota';
COMMENT ON COLUMN route.status     IS 'Status atual da rota: IN_PROGRESS, COMPLETED, COMPLETED_WITH_FAILURES ou CANCELED';
COMMENT ON COLUMN route.created_at IS 'Data e hora de criação da rota';
COMMENT ON COLUMN route.updated_at IS 'Data e hora da última atualização da rota';

-- ============================================================
-- ORDER  (pedido)
-- ============================================================

CREATE TABLE "order" (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    route_id     UUID,
    zip_code     VARCHAR(10)  NOT NULL,
    neighborhood VARCHAR(100) NOT NULL,
    city         VARCHAR(100) NOT NULL,
    state        CHAR(2)      NOT NULL,
    status       order_status NOT NULL DEFAULT 'IN_ROUTE',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_order       PRIMARY KEY (id),
    CONSTRAINT fk_order_route FOREIGN KEY (route_id) REFERENCES route (id) ON DELETE SET NULL
);

COMMENT ON TABLE  "order"              IS 'Pedidos de entrega vinculados às rotas';
COMMENT ON COLUMN "order".id           IS 'Identificador único do pedido';
COMMENT ON COLUMN "order".route_id     IS 'Referência à rota à qual o pedido está associado (pode ser nulo se ainda não alocado)';
COMMENT ON COLUMN "order".zip_code     IS 'CEP do endereço de entrega';
COMMENT ON COLUMN "order".neighborhood IS 'Bairro do endereço de entrega';
COMMENT ON COLUMN "order".city         IS 'Cidade do endereço de entrega';
COMMENT ON COLUMN "order".state        IS 'Sigla do estado (UF) do endereço de entrega';
COMMENT ON COLUMN "order".status       IS 'Status atual do pedido: IN_ROUTE, COLLECTED, DELIVERED, DELIVER_FAILURE ou CANCELED';
COMMENT ON COLUMN "order".created_at   IS 'Data e hora de criação do pedido';
COMMENT ON COLUMN "order".updated_at   IS 'Data e hora da última atualização do pedido';

-- ============================================================
-- ÍNDICES
-- ============================================================

CREATE INDEX idx_driver_vehicle_driver  ON driver_vehicle (driver_id);
CREATE INDEX idx_driver_vehicle_vehicle ON driver_vehicle (vehicle_id);
CREATE INDEX idx_route_driver           ON route (driver_id);
CREATE INDEX idx_route_status           ON route (status);
CREATE INDEX idx_order_route            ON "order" (route_id);
CREATE INDEX idx_order_status           ON "order" (status);
CREATE INDEX idx_order_zip_code         ON "order" (zip_code);
