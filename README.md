# Fausto Importados API

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk&logoColor=white" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/PostgreSQL-15-336791?logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Spring%20Security-JWT-orange?logo=jsonwebtokens&logoColor=white" alt="JWT"/>
  <img src="https://img.shields.io/badge/Flyway-DB--Migrations-red?logo=flyway&logoColor=white" alt="Flyway"/>
  <img src="https://img.shields.io/badge/Swagger-OpenAPI-85EA2D?logo=swagger&logoColor=black" alt="Swagger"/>
  <img src="https://img.shields.io/github/license/Plcc18/fausto-importados-api?color=yellow&logo=open-source-initiative&logoColor=white" alt="License"/>
  <img src="https://img.shields.io/github/languages/top/Plcc18/fausto-importados-api?color=blue&logo=java&logoColor=white" alt="Main Language"/>
  <img src="https://img.shields.io/github/last-commit/Plcc18/fausto-importados-api?logo=git&logoColor=white&color=brightgreen" alt="Last Commit"/>
  <img src="https://img.shields.io/github/repo-size/Plcc18/fausto-importados-api?color=blueviolet&logo=github" alt="Repo Size"/>
</p>

API RESTful para gerenciamento de uma loja de importados, com controle de **produtos**, **pedidos**, **pagamentos** e **autenticação administrativa**.

Desenvolvida com foco em simplicidade, escalabilidade e pronta para integração com frontend e serviços externos.

---

## Funcionalidades

| Módulo           | Funcionalidades                   |
| ---------------- | --------------------------------- |
| **Autenticação** | Login com JWT + 2FA (TOTP) para admins |
| **Produtos**     | CRUD completo + upload de imagens |
| **Pedidos**      | Criação e consulta de pedidos     |
| **Pagamentos**   | Registro e controle de transações |

> Apenas usuários **ADMIN** acessam a área administrativa (2FA obrigatório). Existe também a role `USER`
> (sem acesso administrativo); não há cadastro público. Clientes compram sem login (WhatsApp/frontend).

---

## Tecnologias Utilizadas

* **Java 17**
* **Spring Boot 3.5**
* **Spring Data JPA**
* **Spring Security + JWT**
* **PostgreSQL**
* **Flyway** (migrações de banco)
* **Cloudinary** (armazenamento de imagens)
* **Swagger / OpenAPI**
* **Maven**

---

## Estrutura do Banco de Dados

```sql
users (id, email, password, role)
products (id, name, description, price, image_url, stock)
orders (id, customer_name, total_price, status, created_at)
payments (id, order_id, amount, payment_method, status)
```

> As tabelas possuem restrições de integridade (`NOT NULL`, `FOREIGN KEY`, etc).

---

## Endpoints Principais

### Autenticação

Access token (JWT, 15 min) + refresh token opaco com **sessão deslizante** e rotação.

| Método | Endpoint                  | Descrição                                                                    | Acesso                     |
| ------ | ------------------------- | ---------------------------------------------------------------------------- | -------------------------- |
| POST   | `/auth/login`             | Confere a senha. Admin recebe um desafio de 2FA; os demais abrem a sessão    | Público (limitado)         |
| POST   | `/auth/login/mfa`         | Segundo fator: código do autenticador ou de recuperação → abre a sessão      | Desafio (limitado)         |
| POST   | `/auth/mfa/setup`         | Gera o segredo do 2FA (QR/chave) de uma conta que ainda não o tem            | Desafio                    |
| POST   | `/auth/mfa/setup/confirm` | Confirma o 1º código, ativa o 2FA, devolve os códigos de recuperação         | Desafio (limitado)         |
| POST   | `/auth/refresh`           | Troca o refresh token por um novo e devolve um novo access token             | Cookie + `Origin` + header |
| GET    | `/auth/me`                | Quem está logado: `{ email, role }`                                          | Bearer                     |
| POST   | `/auth/logout`            | Revoga a sessão atual e limpa o cookie                                       | Bearer (aceita já vencido) |
| POST   | `/auth/logout-all`        | Revoga todas as sessões do usuário                                           | Bearer                     |
| POST   | `/auth/reauth`            | Confirma a identidade com um código 2FA → prova de 5 min para o `X-Reauth`   | Bearer (ADMIN, limitado)   |
| POST   | `/auth/password`          | Troca a PRÓPRIA senha (senha atual + reautenticação); derruba as outras sessões | Bearer + `X-Reauth`     |
| POST   | `/auth/mfa/recovery-codes`| Gera 10 códigos de recuperação novos (os antigos deixam de valer)            | Bearer + `X-Reauth`        |

```json
// POST /auth/login
{ "email": "admin@fausto.com", "password": "123456" }
// usuário comum → 200 { "status": "AUTHENTICATED", "accessToken": "<jwt>", "expiresIn": 900 } + Set-Cookie: refresh_token=...
// admin com 2FA  → 200 { "status": "MFA_REQUIRED", "challenge": "<jwt de 5 min>" }            (sem sessão ainda)
// admin sem 2FA  → 200 { "status": "MFA_SETUP_REQUIRED", "challenge": "...", "passwordChangeRequired": false }
// muitas falhas  → 429 + Retry-After
```

**Login do administrador (2FA obrigatório):** senha → `challenge` → `POST /auth/login/mfa { challenge, code }`.
No primeiro acesso (conta sem 2FA): `POST /auth/mfa/setup { challenge }` devolve `secret` (Base32) e `otpauthUri`
(o front desenha o QR); `POST /auth/mfa/setup/confirm { challenge, code, newPassword? }` ativa o 2FA, devolve 10
`recoveryCodes` (aparecem **uma única vez**) e abre a sessão. O `challenge` não vale como access token.

Como funciona:

- **Access token**: JWT assinado com `JWT_SECRET`, vida de 15 min, payload `sub` (id), `role` e `sid` (id da sessão). Enviado em `Authorization: Bearer`; o front o guarda só em memória.
- **Refresh token**: 256 bits aleatórios, no cookie `refresh_token` (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/auth/refresh`). No banco fica **só o hash SHA-256** (tabela `refresh_tokens`).
- **Sessão deslizante**: cada refresh zera o prazo de inatividade (`REFRESH_INACTIVITY`, 7 dias) mas nunca passa do limite absoluto (`REFRESH_ABSOLUTE`, 30 dias).
- **Rotação e reuso**: cada refresh gera um token novo e invalida o anterior, com `SELECT ... FOR UPDATE`. Reapresentar um token já trocado revoga a família inteira, exceto dentro de `REFRESH_GRACE` (10 s), janela para requisições concorrentes legítimas (várias abas): nela só se devolve um novo access token, sem rotacionar de novo.
- **CSRF no refresh**: `SameSite=Strict` + `Origin` na lista de `FRONTEND_URL` + header `X-Requested-With: fausto-web`.
- **Revogação**: logout, "sair de todos" e troca de senha (`PUT /api/user/{id}` com senha) revogam sessões; vale imediatamente porque o filtro confere o `sid` a cada request.
- **Limpeza**: `RefreshTokenCleanupJob` apaga tokens vencidos diariamente (cron em `security.auth.cleanup-cron`).
- Erros de login/refresh são genéricos e não revelam a causa.
- **2FA (TOTP, RFC 6238)**: códigos de 6 dígitos / 30 s via biblioteca `java-otp`; aceita ±1 passo de relógio e
  cada código vale **uma vez** (o último passo aceito fica no banco, atualizado de forma atômica). O segredo é
  cifrado em repouso com **AES-256-GCM** (`TOTP_ENCRYPTION_KEY`; o id do usuário entra como AAD). Códigos de
  recuperação: 10, 80 bits, só o hash SHA-256 é guardado, uso único.
- **Força bruta**: bloqueio temporário progressivo **por conta** (5 falhas → 1 min, dobrando até 30 min) e por IP
  (20 falhas → 5 min...). Senha errada e código 2FA errado somam no mesmo contador, e e-mails inexistentes contam
  igual (o bloqueio não revela se a conta existe). Os contadores ficam no PostgreSQL (`auth_throttle`).
  O IP é o **último** item de `X-Forwarded-For` (o anterior pode ser forjado); atrás do proxy da Vercel esse IP é
  o da própria Vercel, por isso o controle principal é por conta.
- **Primeiro admin**: criado no startup só se não existir nenhum, a partir de `ADMIN_EMAIL`/`ADMIN_PASSWORD`;
  o 2FA é cadastrado no primeiro login e as duas variáveis podem sair do ambiente depois.
- **401 × 403**: sem token (ou token inválido) → 401; autenticado sem permissão (ex.: `USER` numa rota admin) → 403.

> `JWT_EXPIRATION` (24h) não é mais usado. Use `JWT_ACCESS_TTL` (ISO-8601, padrão `PT15M`). Remova a variável antiga do ambiente de produção.

---

### Administradores e auditoria

Cada administrador tem a própria conta (2FA individual). Não há cadastro público: só um admin autenticado cria outro.
Todas as alterações abaixo exigem **reautenticação recente** (`POST /auth/reauth` → header `X-Reauth`); sem ela a
resposta é `403 { "code": "reauth_required" }`.

| Método | Endpoint                          | Descrição                                                              | Acesso            |
| ------ | --------------------------------- | ---------------------------------------------------------------------- | ----------------- |
| GET    | `/api/user`, `/api/user/{id}`     | Lista/consulta contas (`active`, `mfaEnabled`)                         | ADMIN             |
| POST   | `/api/user`                       | Cria admin a partir do e-mail; devolve **senha temporária (uma vez)**  | ADMIN + reauth    |
| PUT    | `/api/user/{id}`                  | Troca o e-mail (a senha NÃO muda por aqui)                             | ADMIN + reauth    |
| PATCH  | `/api/user/{id}/active`           | `{ "active": false }` desativa e encerra as sessões na hora            | ADMIN + reauth    |
| POST   | `/api/user/{id}/reset-credentials`| Senha esquecida / aparelho perdido: nova senha temporária e 2FA a refazer | ADMIN + reauth |
| DELETE | `/api/user/{id}`                  | Exclui a conta (prefira desativar)                                     | ADMIN + reauth    |
| GET    | `/api/admin/audit`                | Trilha de auditoria (`page`, `size`, `action`, `entityType`)           | ADMIN             |

Regras: ninguém desativa/exclui/redefine a **própria** conta por essas rotas e o **último admin ativo** nunca pode ser
removido ou desativado (409). A senha de OUTRA pessoa nunca é definida por um admin: o servidor gera uma senha
temporária de 20 caracteres, mostrada uma única vez, que o novo admin troca ao cadastrar o 2FA em até 24 h
(depois disso a conta só volta com um novo `reset-credentials`).

**Auditoria** (`audit_log`): quem (usuário), o quê (ação/entidade, com antes/depois em alterações de produto), quando,
IP e user-agent. Registra login (sucesso, falha, bloqueio), 2FA, reautenticação, troca de senha, gestão de admins,
criação/alteração/exclusão de produtos, baixa de estoque, pedidos concluídos/cancelados, zerar histórico/vendas/relatório,
reuso de refresh token e acessos negados (403, com limite por minuto). **Nunca** grava senhas, tokens, códigos ou
segredos (chaves sensíveis em `details` viram `[redacted]`). Retenção: 1 ano (`security.audit.retention`, ISO-8601).

---

### Produtos

| Método | Endpoint             | Descrição           | Acesso  |
| ------ | -------------------- | ------------------- | ------- |
| GET    | `/api/products`      | Lista produtos      | Público |
| GET    | `/api/products/{id}` | Busca produto       | Público |
| POST   | `/api/products`      | Cria produto        | ADMIN   |
| PUT    | `/api/products/{id}` | Atualiza produto    | ADMIN   |
| PATCH  | `/api/products/{id}` | Atualização parcial | ADMIN   |
| DELETE | `/api/products/{id}` | Remove produto      | ADMIN   |

---

### Pedidos

| Método | Endpoint           | Descrição       | Acesso  |
| ------ | ------------------ | --------------- | ------- |
| POST   | `/api/orders`      | Cria pedido     | Público |
| GET    | `/api/orders/{id}` | Consulta pedido | ADMIN   |

---

### Pagamentos

| Método | Endpoint        | Descrição          | Acesso |
| ------ | --------------- | ------------------ | ------ |
| POST   | `/api/payments` | Registra pagamento | ADMIN  |

---

## Upload de Imagens

As imagens dos produtos são armazenadas utilizando o **Cloudinary**.

Fluxo:

1. Upload da imagem via API
2. Cloudinary retorna uma URL
3. URL é salva no campo `image_url` do produto

---

## Como Executar

### Pré-requisitos

* Java 17+
* Maven
* PostgreSQL

---

### Passos

1. **Clone o repositório**

```bash
git clone https://github.com/Plcc18/fausto-importados-api.git
cd fausto-importados-api
```

2. **Configure o banco de dados**

```bash
createdb fausto_importados_db
```

3. **Configure as variáveis de ambiente**

A configuração fica em `src/main/resources/application.yml` (YAML), que só lê variáveis
de ambiente — nenhum segredo é commitado. Copie `application.yml.example` como referência
e exporte no seu ambiente/IDE:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/fausto_importados_db
export DB_USERNAME=seu_usuario
export DB_PASSWORD=sua_senha

export JWT_SECRET=$(openssl rand -base64 32)

export CLOUDINARY_NAME=seu_cloud_name
export CLOUDINARY_KEY=sua_api_key
export CLOUDINARY_SECRET=sua_api_secret

# Chave AES-256 (base64, 32 bytes) que cifra os segredos do 2FA. OBRIGATÓRIA — a API não sobe sem ela.
# Guarde uma cópia fora do Render: perdê-la obriga todos os admins a refazer o 2FA.
export TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32)

# Só para criar o PRIMEIRO admin (quando ainda não existe nenhum); depois do 1º login podem sair do ambiente
export ADMIN_EMAIL=admin@fausto.com
export ADMIN_PASSWORD=sua_senha_de_admin
```

4. **Execute a aplicação**

```bash
mvn spring-boot:run
```

---

## Acesso

* API:

```
http://localhost:8080
```

* Swagger:

```
http://localhost:8080/swagger-ui.html
```

---

## Testes Rápidos (curl)

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@fausto.com","password":"123456"}'

# Criar produto
curl -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer SEU_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Perfume X","price":199.90,"stock":10}'
```

---

## Estrutura do Projeto

```
src/
├── main/
│   ├── java/com/fausto/importados/
│   │   ├── controller/     → Endpoints REST
│   │   ├── dto/            → DTOs
│   │   ├── entity/         → Entidades JPA
│   │   ├── repository/     → Repositórios
│   │   ├── service/        → Regras de negócio
│   │   ├── security/       → JWT e configurações
│   │   └── FaustoImportadosApplication.java
│   └── resources/
│       ├── db/migration/   → Scripts Flyway
│       └── application.yml
```

---

## Migração com Flyway

```sql
-- V1__create_tables.sql
CREATE TABLE products (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL
);
```

> Execute:

```bash
mvn flyway:migrate
```

---

## Segurança

* Autenticação via **JWT** (access token curto) + refresh token rotativo
* **2FA (TOTP)** obrigatório para ADMIN, com códigos de recuperação
* Bloqueio temporário progressivo contra força bruta
* Senhas com **BCrypt**
* Acesso restrito para **ADMIN** (401 sem login, 403 sem permissão)
* Endpoints públicos:

    * `/api/auth/**`
    * `/api/products (GET)`

---

## Contribuição

1. Fork do projeto
2. Crie uma branch: `git checkout -b feature/nova-feature`
3. Commit: `git commit -m "feat: nova feature"`
4. Push: `git push origin feature/nova-feature`
5. Abra um Pull Request

---

## Autor

**Plcc18**
[https://github.com/Plcc18](https://github.com/Plcc18)

---

## Licença

Este projeto está sob a licença MIT.
Veja o arquivo `LICENSE` para mais detalhes.

---

**Fausto Importados API** — Backend robusto para e-commerce moderno 🚀
