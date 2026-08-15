CREATE TABLE api_idempotency_key (
  scope varchar(120) NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  request_hash char(64) NOT NULL,
  resource_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX api_idempotency_created_idx ON api_idempotency_key(created_at);
