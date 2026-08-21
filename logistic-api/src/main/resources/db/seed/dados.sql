-- ============================================================
-- Seed Data — totais emergentes (sem números fixos)
-- Banco de dados: PostgreSQL
-- ============================================================

-- ============================================================
-- LIMPEZA (ordem respeita FKs)
-- ============================================================

TRUNCATE TABLE "order"        RESTART IDENTITY CASCADE;
TRUNCATE TABLE route          RESTART IDENTITY CASCADE;
TRUNCATE TABLE driver_vehicle RESTART IDENTITY CASCADE;
TRUNCATE TABLE driver         RESTART IDENTITY CASCADE;
TRUNCATE TABLE vehicle        RESTART IDENTITY CASCADE;

-- ============================================================
-- VEÍCULOS (pequenos, capacidade máx 200 kg = Fiat Toro)
-- ============================================================

INSERT INTO vehicle (id, name, capacity_kg, created_at, updated_at) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Motocicleta Honda CB 300',    8,   '2024-01-05 08:00:00', '2024-01-05 08:00:00'),
    ('a0000000-0000-0000-0000-000000000002', 'Motocicleta Yamaha FZ 250',   6,   '2024-01-11 08:00:00', '2024-01-11 08:00:00'),
    ('a0000000-0000-0000-0000-000000000003', 'Utilitário VW Saveiro',       15,  '2024-01-08 08:00:00', '2024-01-08 08:00:00'),
    ('a0000000-0000-0000-0000-000000000004', 'Furgonete Renault Kangoo',    25,  '2024-01-04 08:00:00', '2024-01-04 08:00:00'),
    ('a0000000-0000-0000-0000-000000000005', 'Van Ford Transit Connect',    40,  '2024-01-09 08:00:00', '2024-01-09 08:00:00'),
    ('a0000000-0000-0000-0000-000000000006', 'Van Peugeot Expert',          60,  '2024-01-07 08:00:00', '2024-01-07 08:00:00'),
    ('a0000000-0000-0000-0000-000000000007', 'Van Volkswagen Delivery',     80,  '2024-01-03 08:00:00', '2024-01-03 08:00:00'),
    ('a0000000-0000-0000-0000-000000000008', 'Furgão Renault Master',       100, '2024-01-06 08:00:00', '2024-01-06 08:00:00'),
    ('a0000000-0000-0000-0000-000000000009', 'Furgão Ford Transit',         120, '2024-01-02 08:00:00', '2024-01-02 08:00:00'),
    ('a0000000-0000-0000-0000-00000000000a', 'Caminhonete Fiat Toro',       200, '2024-01-10 08:00:00', '2024-01-10 08:00:00');

-- ============================================================
-- MOTORISTAS — contagem aleatória por estado (peso demográfico ± variância)
-- Total esperado: ~70–125 motoristas
-- ============================================================

INSERT INTO driver (id, name, email, birthday, city, state, created_at, updated_at)
WITH fn(n, rn) AS (
    SELECT n, row_number() OVER () FROM (VALUES
        ('Carlos'),('Ana'),('Roberto'),('Juliana'),('Marcos'),
        ('Fernanda'),('Ricardo'),('Patricia'),('Lucas'),('Camila'),
        ('Felipe'),('Beatriz'),('Gustavo'),('Larissa'),('Rafael'),
        ('Amanda'),('Thiago'),('Isabela'),('Leonardo'),('Mariana'),
        ('Diego'),('Vanessa'),('Andre'),('Renata'),('Bruno'),
        ('Tatiane'),('Rodrigo'),('Priscila'),('Henrique'),('Cristiane')
    ) t(n)
),
ln(n, rn) AS (
    SELECT n, row_number() OVER () FROM (VALUES
        ('Silva'),('Santos'),('Oliveira'),('Ferreira'),('Souza'),
        ('Almeida'),('Costa'),('Pereira'),('Rodrigues'),('Lima'),
        ('Gomes'),('Ribeiro'),('Martins'),('Carvalho'),('Araujo'),
        ('Moreira'),('Nunes'),('Machado'),('Duarte'),('Andrade'),
        ('Teixeira'),('Barbosa'),('Castro'),('Mendes'),('Lopes'),
        ('Freitas'),('Cardoso'),('Ramos'),('Torres'),('Melo')
    ) t(n)
),
state_drv_config(state_code, base_drv, var_drv, min_drv) AS (
    VALUES
        ('SP', 22, 8, 5),
        ('MG', 12, 4, 3),
        ('RJ', 10, 4, 3),
        ('BA',  9, 3, 3),
        ('PR',  8, 3, 3),
        ('RS',  8, 3, 3),
        ('GO',  6, 2, 2),
        ('SC',  6, 2, 2),
        ('ES',  5, 2, 2),
        ('MS',  4, 2, 2)
),
state_cities(state_code, cities) AS (
    VALUES
        ('SP', ARRAY['São Paulo','Campinas','Santos','Guarulhos','Osasco','Ribeirão Preto','Sorocaba','São Bernardo do Campo','Barueri','Carapicuíba','Jundiaí','Piracicaba','São José dos Campos','Mogi das Cruzes','Diadema','Mauá','Santo André','Franca','Presidente Prudente','Taubaté']),
        ('MG', ARRAY['Belo Horizonte','Uberlândia','Contagem','Juiz de Fora','Betim','Montes Claros','Ribeirão das Neves','Uberaba','Governador Valadares','Ipatinga','Sete Lagoas','Divinópolis','Patos de Minas']),
        ('RJ', ARRAY['Rio de Janeiro','Niterói','São Gonçalo','Duque de Caxias','Nova Iguaçu','Campos dos Goytacazes','Belford Roxo','São João de Meriti','Petrópolis','Volta Redonda','Macaé']),
        ('BA', ARRAY['Salvador','Feira de Santana','Vitória da Conquista','Camaçari','Juazeiro','Itabuna','Lauro de Freitas','Ilhéus','Jequié','Barreiras']),
        ('PR', ARRAY['Curitiba','Londrina','Maringá','Ponta Grossa','Cascavel','São José dos Pinhais','Foz do Iguaçu','Colombo','Guarapuava','Paranaguá']),
        ('RS', ARRAY['Porto Alegre','Caxias do Sul','Pelotas','Canoas','Santa Maria','Gravataí','Viamão','Novo Hamburgo','São Leopoldo','Rio Grande']),
        ('GO', ARRAY['Goiânia','Aparecida de Goiânia','Anápolis','Rio Verde','Luziânia','Águas Lindas','Valparaíso de Goiás','Trindade','Formosa','Novo Gama']),
        ('SC', ARRAY['Florianópolis','Joinville','Blumenau','São José','Chapecó','Itajaí','Criciúma','Jaraguá do Sul','Lages','Palhoça']),
        ('ES', ARRAY['Vitória','Vila Velha','Serra','Cariacica','Cachoeiro de Itapemirim','Linhares','São Mateus','Colatina','Guarapari','Aracruz']),
        ('MS', ARRAY['Campo Grande','Dourados','Três Lagoas','Corumbá','Ponta Porã','Naviraí','Nova Andradina','Aquidauana','Sidrolândia','Maracaju'])
),
drv_counts AS (
    SELECT
        sdc.state_code,
        sc.cities,
        GREATEST(sdc.min_drv,
            sdc.base_drv + floor((random() - 0.5) * 2 * sdc.var_drv)::int
        ) AS n_drivers
    FROM state_drv_config sdc
    JOIN state_cities sc ON sc.state_code = sdc.state_code
),
drv_series AS (
    SELECT
        row_number() OVER () AS seq,
        dc.state_code,
        dc.cities
    FROM drv_counts dc,
    LATERAL generate_series(1, dc.n_drivers) AS gs(i)
)
SELECT
    ('b0000000-0000-0000-0000-000000000' || lpad(to_hex(ds.seq::int), 3, '0'))::UUID,
    fn.n || ' ' || ln.n,
    lower(fn.n) || '.' || lower(ln.n) || '.' || ds.seq || '@email.com',
    date '1958-01-01' + (floor(random() * 16436))::int,
    cities[1 + floor(random() * array_length(cities, 1))::int],
    state_code,
    CURRENT_TIMESTAMP - (floor(random() * 730))::int * interval '1 day',
    CURRENT_TIMESTAMP - (floor(random() * 60))::int  * interval '1 day'
FROM drv_series ds
JOIN fn ON fn.rn = 1 + ((ds.seq - 1) % 30)
JOIN ln ON ln.rn = 1 + ((ds.seq + 9) % 30);

-- ============================================================
-- DRIVER_VEHICLE — 1 veículo aleatório por motorista
-- ============================================================

INSERT INTO driver_vehicle (driver_id, vehicle_id, created_at)
SELECT
    d.id,
    ('a0000000-0000-0000-0000-000000000' || lpad(to_hex((floor(random() * 10))::int + 1), 3, '0'))::UUID,
    CURRENT_TIMESTAMP - (floor(random() * 365))::int * interval '1 day'
FROM driver d;

-- ============================================================
-- ROTAS — contagem aleatória por estado (sem IN_PROGRESS)
-- IDs: 0001–FFFF (sem conflito com IDs IN_PROGRESS que começam em 9001)
-- ============================================================

INSERT INTO route (id, driver_id, status, created_at, updated_at)
WITH state_route_config(state_code, base_routes, var_routes) AS (
    VALUES
        ('SP', 420, 130),
        ('MG', 155,  65),
        ('RJ', 120,  50),
        ('BA',  95,  40),
        ('PR',  75,  30),
        ('RS',  75,  30),
        ('GO',  45,  20),
        ('SC',  45,  20),
        ('ES',  32,  15),
        ('MS',  22,  10)
),
state_drivers AS (
    SELECT d.state,
           array_agg(d.id ORDER BY d.id) AS ids,
           count(*)::int                 AS cnt
    FROM driver d
    GROUP BY d.state
),
route_counts AS (
    SELECT src.state_code, sd.ids, sd.cnt,
           GREATEST(sd.cnt * 2,
               (src.base_routes + floor((random() - 0.5) * 2 * src.var_routes))::int
           ) AS n_routes
    FROM state_route_config src
    JOIN state_drivers sd ON sd.state = src.state_code
),
route_rows AS (
    SELECT
        row_number() OVER () AS rn,
        rc.ids[1 + floor(random() * rc.cnt)::int] AS driver_id
    FROM route_counts rc,
    LATERAL generate_series(1, rc.n_routes) AS gs(i)
),
route_with_time AS (
    SELECT rn, driver_id,
           CURRENT_TIMESTAMP - (floor(random() * 730 * 86400))::int * interval '1 second' AS created
    FROM route_rows
)
SELECT
    ('c0000000-0000-0000-0000-00000000' || lpad(to_hex(rn::int), 4, '0'))::UUID,
    driver_id,
    (CASE (floor(random() * 3))::int
         WHEN 0 THEN 'COMPLETED'
         WHEN 1 THEN 'COMPLETED_WITH_FAILURES'
         ELSE        'CANCELED'
    END)::route_status,
    created,
    LEAST(created + (floor(random() * 1440))::int * interval '1 minute', CURRENT_TIMESTAMP)
FROM route_with_time;

-- ============================================================
-- ROTAS IN_PROGRESS — exatamente 1 por motorista
-- IDs: 9001+ (sem conflito com batch acima)
-- ============================================================

INSERT INTO route (id, driver_id, status, created_at, updated_at)
SELECT
    ('c0000000-0000-0000-0000-00000000' ||
        lpad(to_hex((9000 + row_number() OVER (ORDER BY d.id))::int), 4, '0'))::UUID,
    d.id,
    'IN_PROGRESS'::route_status,
    CURRENT_TIMESTAMP - (floor(random() * 72) + 1)::int * interval '1 hour',
    CURRENT_TIMESTAMP - floor(random() * 60)::int  * interval '1 minute'
FROM driver d;

-- ============================================================
-- PEDIDOS — 2–20 por rota; cidade/estado herdados do motorista
-- Total emergente: sem número fixo
-- ============================================================

INSERT INTO "order" (id, route_id, zip_code, neighborhood, city, state, status, created_at, updated_at)
WITH all_routes AS (
    SELECT r.id AS route_id, d.state AS drv_state, r.created_at AS route_created
    FROM route r
    JOIN driver d ON d.id = r.driver_id
),
order_series AS (
    SELECT
        row_number() OVER () AS order_num,
        ar.route_id,
        ar.drv_state,
        ar.route_created
    FROM all_routes ar,
    LATERAL generate_series(1, 2 + floor(random() * 19)::int) AS gs(i)
)
SELECT
    ('d0000000-0000-0000-0000-0000000' || lpad(to_hex(order_num::int), 5, '0'))::UUID,

    route_id,

    lpad(((floor(random() * 90000))::int + 10000)::text, 5, '0')
        || '-' ||
    lpad(((floor(random() * 900))::int + 100)::text, 3, '0'),

    (ARRAY[
        'Centro','Jardim Paulista','Bela Vista','Pinheiros',
        'Moema','Vila Madalena','Itaim Bibi','Santana',
        'Tatuapé','Penha','Lapa','Perdizes',
        'Brooklin','Vila Mariana','São Pedro','Consolação',
        'Ipiranga','Carrão','Sapopemba','Jaçanã'
    ])[1 + floor(random() * 20)::int],

    CASE drv_state
        WHEN 'SP' THEN (ARRAY['São Paulo','Campinas','Santos','Guarulhos','Osasco','Ribeirão Preto','Sorocaba','São Bernardo do Campo','Barueri','Carapicuíba','Jundiaí','Piracicaba','São José dos Campos','Mogi das Cruzes','Diadema','Mauá','Santo André','Franca','Presidente Prudente','Taubaté'])[1 + floor(random() * 20)::int]
        WHEN 'MG' THEN (ARRAY['Belo Horizonte','Uberlândia','Contagem','Juiz de Fora','Betim','Montes Claros','Ribeirão das Neves','Uberaba','Governador Valadares','Ipatinga','Sete Lagoas','Divinópolis','Patos de Minas'])[1 + floor(random() * 13)::int]
        WHEN 'RJ' THEN (ARRAY['Rio de Janeiro','Niterói','São Gonçalo','Duque de Caxias','Nova Iguaçu','Campos dos Goytacazes','Belford Roxo','São João de Meriti','Petrópolis','Volta Redonda','Macaé'])[1 + floor(random() * 11)::int]
        WHEN 'BA' THEN (ARRAY['Salvador','Feira de Santana','Vitória da Conquista','Camaçari','Juazeiro','Itabuna','Lauro de Freitas','Ilhéus','Jequié','Barreiras'])[1 + floor(random() * 10)::int]
        WHEN 'PR' THEN (ARRAY['Curitiba','Londrina','Maringá','Ponta Grossa','Cascavel','São José dos Pinhais','Foz do Iguaçu','Colombo','Guarapuava','Paranaguá'])[1 + floor(random() * 10)::int]
        WHEN 'RS' THEN (ARRAY['Porto Alegre','Caxias do Sul','Pelotas','Canoas','Santa Maria','Gravataí','Viamão','Novo Hamburgo','São Leopoldo','Rio Grande'])[1 + floor(random() * 10)::int]
        WHEN 'GO' THEN (ARRAY['Goiânia','Aparecida de Goiânia','Anápolis','Rio Verde','Luziânia','Águas Lindas','Valparaíso de Goiás','Trindade','Formosa','Novo Gama'])[1 + floor(random() * 10)::int]
        WHEN 'SC' THEN (ARRAY['Florianópolis','Joinville','Blumenau','São José','Chapecó','Itajaí','Criciúma','Jaraguá do Sul','Lages','Palhoça'])[1 + floor(random() * 10)::int]
        WHEN 'ES' THEN (ARRAY['Vitória','Vila Velha','Serra','Cariacica','Cachoeiro de Itapemirim','Linhares','São Mateus','Colatina','Guarapari','Aracruz'])[1 + floor(random() * 10)::int]
        WHEN 'MS' THEN (ARRAY['Campo Grande','Dourados','Três Lagoas','Corumbá','Ponta Porã','Naviraí','Nova Andradina','Aquidauana','Sidrolândia','Maracaju'])[1 + floor(random() * 10)::int]
        ELSE           (ARRAY['São Paulo','Rio de Janeiro','Salvador'])[1 + floor(random() * 3)::int]
    END,

    drv_state,

    (CASE (floor(random() * 5))::int
        WHEN 0 THEN 'DELIVERED'
        WHEN 1 THEN 'IN_ROUTE'
        WHEN 2 THEN 'COLLECTED'
        WHEN 3 THEN 'DELIVER_FAILURE'
        ELSE        'CANCELED'
    END)::order_status,

    route_created + floor(random() * 120)::int  * interval '1 minute',
    LEAST(route_created + floor(random() * 2880)::int * interval '1 minute', CURRENT_TIMESTAMP)

FROM order_series;
