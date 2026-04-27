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
  olfactive_family VARCHAR NOT NULL,
  category VARCHAR NOT NULL,
  size VARCHAR(50) NOT NULL,
  price NUMERIC(10,2) NOT NULL,
  original_price NUMERIC(10,2),
  image TEXT NOT NULL,
  featured BOOLEAN DEFAULT FALSE,
  in_stock BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  stock_quantity INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(150) UNIQUE NOT NULL,
  password TEXT NOT NULL,
  role VARCHAR(30) NOT NULL,
  active BOOLEAN DEFAULT TRUE
);

CREATE TABLE orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_name VARCHAR(255) NOT NULL,
  customer_whatsapp VARCHAR(255) NOT NULL,
  payment_method VARCHAR(50) NOT NULL,
  total NUMERIC(10,2) NOT NULL,
  hidden_from_panel BOOLEAN NOT NULL DEFAULT FALSE,
  hidden_from_report BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id UUID NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  product_size VARCHAR(50) NOT NULL,
  product_category VARCHAR(50),
  product_family VARCHAR(50),
  on_sale BOOLEAN NOT NULL DEFAULT FALSE,
  quantity INTEGER NOT NULL,
  unit_price NUMERIC(10,2) NOT NULL
);