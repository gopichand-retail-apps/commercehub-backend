CREATE TABLE synthetic_sessions (
  id UUID PRIMARY KEY,
  role VARCHAR(16) NOT NULL CHECK (role IN ('CUSTOMER', 'MERCHANT', 'SUPPORT')),
  token_hash CHAR(64) NOT NULL UNIQUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at TIMESTAMP WITH TIME ZONE
);

ALTER TABLE customer_orders ALTER COLUMN access_token_hash DROP NOT NULL;
ALTER TABLE customer_orders
  ADD COLUMN owner_session_id UUID REFERENCES synthetic_sessions(id);

CREATE INDEX idx_customer_orders_owner_session_id ON customer_orders(owner_session_id);
CREATE INDEX idx_synthetic_sessions_expires_at ON synthetic_sessions(expires_at);
