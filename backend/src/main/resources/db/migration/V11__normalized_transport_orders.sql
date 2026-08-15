ALTER TABLE warehouse_request RENAME TO transport_order;
ALTER TABLE request_load RENAME TO transport_order_load;
ALTER TABLE job RENAME TO transport_task;
ALTER TABLE agv RENAME COLUMN job_id TO task_id;

ALTER TABLE transport_order RENAME COLUMN request_type TO order_type;
ALTER TABLE transport_order RENAME COLUMN prompt TO objective;
ALTER TABLE transport_order ADD COLUMN priority varchar(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE transport_order ADD COLUMN scenario_id varchar(40);
ALTER TABLE transport_order ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE transport_task ADD COLUMN assigned_agv_id varchar(60);
ALTER TABLE transport_task ADD COLUMN accepted_at timestamptz;
ALTER TABLE transport_task ADD COLUMN started_at timestamptz;
ALTER TABLE transport_task ADD COLUMN completed_at timestamptz;
ALTER TABLE transport_task ADD COLUMN error text;

ALTER TABLE warehouse_runtime ADD COLUMN scenario_id varchar(40);
ALTER TABLE warehouse_runtime ADD COLUMN scenario_configured boolean NOT NULL DEFAULT false;

CREATE TABLE vda_dispatch (
  id uuid PRIMARY KEY,
  task_id uuid REFERENCES transport_task(id) ON DELETE CASCADE,
  manufacturer varchar(120) NOT NULL,
  serial_number varchar(120) NOT NULL,
  order_id varchar(120) NOT NULL,
  order_update_id bigint NOT NULL,
  status varchar(30) NOT NULL,
  payload_json text NOT NULL,
  validation_error text,
  rejection_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  accepted_at timestamptz,
  finished_at timestamptz,
  UNIQUE(order_id, order_update_id)
);

CREATE INDEX transport_order_priority_idx ON transport_order(priority, created_at);
CREATE INDEX transport_task_status_idx ON transport_task(status, created_at);
CREATE INDEX vda_dispatch_task_idx ON vda_dispatch(task_id, created_at);
