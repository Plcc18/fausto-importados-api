-- Enum para categoria
CREATE TYPE product_category AS ENUM ('masculino', 'feminino', 'unissex');

-- Enum para família olfativa
CREATE TYPE olfactive_family AS ENUM (
  'floral',
  'amadeirado',
  'citrico',
  'oriental',
  'aquatico',
  'frutado',
  'gourmand'
);

CREATE TABLE products (
  id UUID PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  brand VARCHAR(100) NOT NULL,
  description TEXT NOT NULL,
  olfactive_family olfactive_family NOT NULL,
  category product_category NOT NULL,
  size VARCHAR(50) NOT NULL,
  price NUMERIC(10,2) NOT NULL,
  original_price NUMERIC(10,2),
  image TEXT NOT NULL,
  featured BOOLEAN DEFAULT FALSE,
  in_stock BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(150) UNIQUE NOT NULL,
  password TEXT NOT NULL,
  role VARCHAR(30) NOT NULL,
  active BOOLEAN DEFAULT TRUE
)
