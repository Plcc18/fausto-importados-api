-- Constraints: última linha de defesa contra dados inconsistentes, mesmo que a aplicação erre.
-- Antes de implantar, rode scripts/preflight-constraints.sql no banco (ver docs/OPERATIONS.md).

-- 1) Índices únicos não podem ser criados sobre dados duplicados. Falha com a lista do que precisa ser corrigido
--    (a migration inteira é desfeita e a versão anterior da API continua no ar).
DO $$
DECLARE
  duplicated TEXT;
BEGIN
  SELECT string_agg(email, ', ') INTO duplicated
    FROM (SELECT lower(email) AS email FROM users GROUP BY lower(email) HAVING count(*) > 1) d;
  IF duplicated IS NOT NULL THEN
    RAISE EXCEPTION 'V9: e-mails repetidos (ignorando maiusculas): %', duplicated;
  END IF;

  SELECT string_agg(ids, ' | ') INTO duplicated
    FROM (SELECT string_agg(id::text, ', ') AS ids FROM products WHERE active
           GROUP BY lower(btrim(name)), lower(btrim(brand)), lower(btrim(size)) HAVING count(*) > 1) d;
  IF duplicated IS NOT NULL THEN
    RAISE EXCEPTION 'V9: produtos ativos repetidos (mesmo nome, marca e tamanho), ids: %', duplicated;
  END IF;
END $$;

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE UNIQUE INDEX uq_products_identity
  ON products (lower(btrim(name)), lower(btrim(brand)), lower(btrim(size)))
  WHERE active;

-- 2) No máximo um refresh token vivo por sessão. Se uma corrida antiga deixou dois, a sessão é encerrada.
UPDATE refresh_tokens SET revoked_at = now()
 WHERE revoked_at IS NULL AND rotated_at IS NULL
   AND family_id IN (SELECT family_id FROM refresh_tokens
                      WHERE revoked_at IS NULL AND rotated_at IS NULL
                      GROUP BY family_id HAVING count(*) > 1);

CREATE UNIQUE INDEX uq_refresh_tokens_live
  ON refresh_tokens (family_id)
  WHERE revoked_at IS NULL AND rotated_at IS NULL;

-- 3) CHECKs e FK entram como NOT VALID (valem já para qualquer escrita nova) e são validados em seguida. Se houver
--    dado antigo fora da regra, a implantação não falha: fica um WARNING no log e a constraint segue sem validação.
ALTER TABLE products
  ADD CONSTRAINT chk_products_stock          CHECK (stock_quantity >= 0) NOT VALID,
  ADD CONSTRAINT chk_products_price          CHECK (price > 0) NOT VALID,
  ADD CONSTRAINT chk_products_original_price CHECK (original_price IS NULL OR original_price > 0) NOT VALID;

ALTER TABLE orders
  ADD CONSTRAINT chk_orders_total  CHECK (total >= 0) NOT VALID,
  ADD CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED')) NOT VALID;

ALTER TABLE order_items
  ADD CONSTRAINT chk_order_items_quantity   CHECK (quantity > 0) NOT VALID,
  ADD CONSTRAINT chk_order_items_unit_price CHECK (unit_price >= 0) NOT VALID,
  ADD CONSTRAINT fk_order_items_product     FOREIGN KEY (product_id) REFERENCES products (id) NOT VALID;

ALTER TABLE users
  ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')) NOT VALID;

DO $$
DECLARE
  item RECORD;
BEGIN
  FOR item IN
    SELECT * FROM (VALUES
      ('products', 'chk_products_stock'),
      ('products', 'chk_products_price'),
      ('products', 'chk_products_original_price'),
      ('orders', 'chk_orders_total'),
      ('orders', 'chk_orders_status'),
      ('order_items', 'chk_order_items_quantity'),
      ('order_items', 'chk_order_items_unit_price'),
      ('order_items', 'fk_order_items_product'),
      ('users', 'chk_users_role')
    ) AS c (table_name, constraint_name)
  LOOP
    BEGIN
      EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', item.table_name, item.constraint_name);
    EXCEPTION WHEN check_violation OR foreign_key_violation THEN
      RAISE WARNING 'V9: ha dados antigos fora da regra % em %; corrija e rode VALIDATE CONSTRAINT',
        item.constraint_name, item.table_name;
    END;
  END LOOP;
END $$;
