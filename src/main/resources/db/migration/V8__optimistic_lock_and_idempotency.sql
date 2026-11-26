-- Lock otimista: cada alteração incrementa a versão; uma edição feita sobre uma versão antiga é recusada (409).
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Idempotency-Key das rotas POST sensíveis. A chave primária decide quem executa; a linha é gravada na mesma
-- transação da operação. Apagada depois de app.concurrency.idempotency-retention (padrão 1 dia).
-- NUNCA guarda senhas temporárias (o corpo é gravado sem elas).
CREATE TABLE idempotency_keys (
  op_scope         VARCHAR(200) NOT NULL,
  idem_key         VARCHAR(100) NOT NULL,
  request_hash     VARCHAR(64)  NOT NULL,
  response_status  INTEGER,
  response_body    TEXT,
  created_at       TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (op_scope, idem_key)
);

CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);
