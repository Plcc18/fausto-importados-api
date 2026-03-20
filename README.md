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
| **Autenticação** | Login com JWT (admin)             |
| **Produtos**     | CRUD completo + upload de imagens |
| **Pedidos**      | Criação e consulta de pedidos     |
| **Pagamentos**   | Registro e controle de transações |

> Apenas usuários **ADMIN** possuem acesso autenticado ao sistema.
> Clientes não possuem login — o fluxo de compra pode ser integrado com WhatsApp ou frontend.

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

| Método | Endpoint          | Descrição     | Acesso  |
| ------ | ----------------- | ------------- | ------- |
| POST   | `/api/auth/login` | Login com JWT | Público |

#### Exemplo de Login

```json
{
  "email": "admin@fausto.com",
  "password": "123456"
}
```

→ Retorna: `JWT Token`

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

3. **Configure o `application.properties`**

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/fausto_importados_db
spring.datasource.username=seu_usuario
spring.datasource.password=sua_senha

spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true

spring.jwt.secret=sua_chave_secreta

cloudinary.cloud-name=seu_cloud_name
cloudinary.api-key=sua_api_key
cloudinary.api-secret=sua_api_secret
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
│       └── application.properties
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

* Autenticação via **JWT**
* Senhas com **BCrypt**
* Acesso restrito para **ADMIN**
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
