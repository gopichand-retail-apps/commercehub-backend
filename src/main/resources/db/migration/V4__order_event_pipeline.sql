CREATE TABLE outbox_event (
  event_id UUID PRIMARY KEY,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id UUID NOT NULL REFERENCES customer_orders(id),
  aggregate_version BIGINT NOT NULL CHECK (aggregate_version > 0),
  event_type VARCHAR(64) NOT NULL,
  schema_version INT NOT NULL,
  payload_json VARCHAR(8192) NOT NULL,
  occurred_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at_utc TIMESTAMP WITH TIME ZONE,
  attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
  last_error_code VARCHAR(64),
  claim_id UUID,
  claim_until TIMESTAMP WITH TIME ZONE,
  UNIQUE (aggregate_id, aggregate_version)
);
CREATE INDEX idx_outbox_pending ON outbox_event(published_at_utc, next_attempt_at_utc);

CREATE TABLE consumer_inbox (
  consumer_name VARCHAR(32) NOT NULL,
  event_id UUID NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  processed_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (consumer_name, event_id)
);
CREATE TABLE order_projection (
  order_id UUID PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  total_minor_units BIGINT NOT NULL CHECK (total_minor_units >= 0),
  currency CHAR(3) NOT NULL,
  version BIGINT NOT NULL,
  last_event_id UUID NOT NULL,
  last_updated_at_utc TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE notification_log (
  event_id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (status = 'SIMULATED')
);
CREATE TABLE consumer_state (
  consumer_name VARCHAR(32) PRIMARY KEY,
  last_success TIMESTAMP WITH TIME ZONE,
  duplicate_count BIGINT NOT NULL DEFAULT 0
);
INSERT INTO consumer_state(consumer_name) VALUES ('reporting'), ('notification');
CREATE TABLE event_quarantine (
  record_id CHAR(64) PRIMARY KEY,
  consumer_name VARCHAR(32) NOT NULL,
  event_id UUID,
  payload_hash CHAR(64) NOT NULL,
  reason VARCHAR(64) NOT NULL,
  topic_name VARCHAR(120) NOT NULL,
  partition_id INT NOT NULL,
  record_offset BIGINT NOT NULL,
  created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
  resolved_at_utc TIMESTAMP WITH TIME ZONE
);
CREATE TABLE event_replay_audit (
  request_key VARCHAR(128) PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES outbox_event(event_id),
  actor_session_id UUID NOT NULL,
  created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL
);
