-- Adiciona coluna de quantidade em estoque na tabela de produtos.
-- Produtos existentes recebem 0 por padrão e são marcados como fora de estoque.
-- Ajuste o valor de stock_quantity manualmente pelo painel admin após rodar esta migration.

ALTER TABLE products
    ADD COLUMN stock_quantity INTEGER NOT NULL DEFAULT 0;