CREATE TABLE IF NOT EXISTS products (
  id UUID PRIMARY KEY,
  slug VARCHAR(120) NOT NULL UNIQUE,
  name VARCHAR(160) NOT NULL,
  subtitle VARCHAR(240) NOT NULL,
  description VARCHAR(1200) NOT NULL,
  category VARCHAR(60) NOT NULL,
  price_minor BIGINT NOT NULL CHECK (price_minor >= 0),
  stock_quantity INTEGER NOT NULL CHECK (stock_quantity >= 0),
  image_url VARCHAR(1000) NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  featured BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS customer_orders (
  id UUID PRIMARY KEY,
  access_token_hash CHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  subtotal_minor BIGINT NOT NULL CHECK (subtotal_minor >= 0),
  discount_minor BIGINT NOT NULL CHECK (discount_minor >= 0),
  shipping_minor BIGINT NOT NULL CHECK (shipping_minor >= 0),
  tax_minor BIGINT NOT NULL CHECK (tax_minor >= 0),
  total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
  tax_basis_points SMALLINT NOT NULL CHECK (tax_basis_points BETWEEN 0 AND 10000),
  promo_code VARCHAR(32),
  payment_method VARCHAR(32) NOT NULL CHECK (payment_method = 'SIMULATED'),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_lines (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES customer_orders(id),
  product_id UUID NOT NULL REFERENCES products(id),
  product_name VARCHAR(160) NOT NULL,
  product_image_url VARCHAR(1000) NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
  discount_minor BIGINT NOT NULL CHECK (discount_minor >= 0),
  tax_minor BIGINT NOT NULL CHECK (tax_minor >= 0),
  line_total_minor BIGINT NOT NULL CHECK (line_total_minor >= 0)
);

CREATE INDEX IF NOT EXISTS idx_products_category_available ON products(category, available);
CREATE INDEX IF NOT EXISTS idx_order_lines_order_id ON order_lines(order_id);
