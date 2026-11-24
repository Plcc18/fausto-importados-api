#!/usr/bin/env bash
# Restaura um backup gerado por db-backup.sh.
#
# ATENÇÃO: --clean apaga e recria os objetos do banco de destino. Para um teste de restauração use um banco
# NOVO e vazio (createdb); para recuperar produção, confirme explicitamente com CONFIRM_RESTORE=yes.
#
# A chave PRIVADA GnuPG precisa estar disponível no gpg de quem restaura (o gpg pede a senha, se houver).
#
# Uso: CONFIRM_RESTORE=yes scripts/db-restore.sh backup.dump.gpg postgres://usuario:senha@host:5432/banco_destino
set -euo pipefail

file="${1:?uso: db-restore.sh <backup.dump.gpg> <url-do-banco-destino>}"
target="${2:?uso: db-restore.sh <backup.dump.gpg> <url-do-banco-destino>}"

if [ "${CONFIRM_RESTORE:-}" != "yes" ]; then
  echo "Isto vai SOBRESCREVER o banco de destino. Rode de novo com CONFIRM_RESTORE=yes se é isso mesmo que você quer." >&2
  exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo ">> decifrando"
gpg --batch --yes --output "$tmp" --decrypt "$file"

echo ">> restaurando em $(printf '%s' "$target" | sed -E 's#(://[^:/@]+):[^@]*@#\1:***@#')"
pg_restore --clean --if-exists --no-owner --no-acl --exit-on-error --dbname "$target" "$tmp"

echo ">> pronto. Suba a API apontando para este banco; o Flyway confere/aplica as migrations pendentes."
