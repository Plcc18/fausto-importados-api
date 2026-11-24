#!/usr/bin/env bash
# Backup cifrado do PostgreSQL.
#
#   pg_dump (formato custom) -> confere que o dump é legível -> (opcional) restaura num banco de teste
#   e confere o conteúdo -> cifra com a CHAVE PÚBLICA GnuPG. O dump em claro nunca fica no disco depois.
#
# Só a chave PÚBLICA precisa estar no servidor/CI; a privada fica offline com quem restaura.
#
# Variáveis:
#   DATABASE_URL           (obrigatória) URL do banco de produção, ex.: postgres://usuario:senha@host:5432/banco
#   BACKUP_GPG_RECIPIENT   (obrigatória) fingerprint ou e-mail da chave pública já importada no gpg
#   VERIFY_DATABASE_URL    (opcional) banco VAZIO descartável; se informado, o dump é restaurado nele e conferido
#
# Uso: scripts/db-backup.sh [arquivo-de-saida.dump.gpg]
set -euo pipefail

: "${DATABASE_URL:?defina DATABASE_URL}"
: "${BACKUP_GPG_RECIPIENT:?defina BACKUP_GPG_RECIPIENT (fingerprint da chave pública)}"

out="${1:-backup-$(date -u +%Y%m%dT%H%M%SZ).dump.gpg}"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo ">> pg_dump"
pg_dump --format=custom --no-owner --no-acl --file="$tmp" "$DATABASE_URL"

echo ">> conferindo que o dump é legível"
pg_restore --list "$tmp" > /dev/null
tables="$(pg_restore --list "$tmp" | grep -c ' TABLE DATA ' || true)"
if [ "$tables" -lt 3 ]; then
  echo "ERRO: o dump tem só $tables tabelas com dados — algo está errado, backup descartado." >&2
  exit 1
fi

if [ -n "${VERIFY_DATABASE_URL:-}" ]; then
  echo ">> restaurando num banco de teste para conferir"
  pg_restore --no-owner --no-acl --dbname "$VERIFY_DATABASE_URL" "$tmp"
  for table in users products orders flyway_schema_history; do
    psql "$VERIFY_DATABASE_URL" --no-psqlrc --tuples-only --no-align \
      --command "select count(*) from $table" > /dev/null \
      || { echo "ERRO: a restauração de teste não tem a tabela $table." >&2; exit 1; }
  done
  versions="$(psql "$VERIFY_DATABASE_URL" --no-psqlrc --tuples-only --no-align \
    --command "select count(*) from flyway_schema_history where success")"
  if [ "$versions" -lt 1 ]; then
    echo "ERRO: a restauração de teste não tem nenhuma migration aplicada." >&2
    exit 1
  fi
  echo "   ok: $versions migrations, tabelas principais presentes"
fi

echo ">> cifrando para $BACKUP_GPG_RECIPIENT"
gpg --batch --yes --trust-model always --recipient "$BACKUP_GPG_RECIPIENT" --output "$out" --encrypt "$tmp"

echo ">> pronto: $out ($(wc -c < "$out") bytes)"
