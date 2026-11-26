-- Rode ANTES de implantar a versão com a migration V9. Só faz leitura.
--   psql "$DATABASE_URL" -f scripts/preflight-constraints.sql
--
-- Resultado esperado: todas as consultas sem linhas.

\echo '1) IMPEDE a implantação: e-mails repetidos ignorando maiúsculas'
SELECT lower(email) AS email, count(*) AS contas, string_agg(id::text, ', ') AS ids
  FROM users GROUP BY lower(email) HAVING count(*) > 1;

\echo '2) IMPEDE a implantação: produtos ativos com mesmo nome, marca e tamanho (desative ou renomeie os extras)'
SELECT min(name) AS nome, min(brand) AS marca, min(size) AS tamanho, string_agg(id::text, ', ') AS ids
  FROM products WHERE active
 GROUP BY lower(btrim(name)), lower(btrim(brand)), lower(btrim(size)) HAVING count(*) > 1;

\echo '3) Aviso: produtos com estoque negativo ou preço inválido'
SELECT id, name, stock_quantity, price, original_price FROM products
 WHERE stock_quantity < 0 OR price <= 0 OR original_price <= 0;

\echo '4) Aviso: pedidos com total negativo ou status desconhecido'
SELECT id, total, status FROM orders
 WHERE total < 0 OR status NOT IN ('PENDING', 'COMPLETED', 'CANCELLED');

\echo '5) Aviso: itens com quantidade ou preço inválido'
SELECT id, order_id, quantity, unit_price FROM order_items WHERE quantity <= 0 OR unit_price < 0;

\echo '6) Aviso: itens que apontam para produtos já apagados (esperado em bancos antigos; o histórico é mantido)'
SELECT i.id, i.order_id, i.product_id, i.product_name FROM order_items i
 WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.id = i.product_id);

\echo '7) Aviso: contas com papel desconhecido'
SELECT id, email, role FROM users WHERE role NOT IN ('USER', 'ADMIN');
