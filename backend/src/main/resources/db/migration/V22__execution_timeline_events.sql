-- Record immutable execution timeline events for transport order, task, and vehicle transitions.
CREATE TABLE execution_event (
  id uuid PRIMARY KEY,
  transport_order_id uuid NOT NULL REFERENCES transport_order(id) ON DELETE CASCADE,
  transport_task_id uuid NOT NULL REFERENCES transport_task(id) ON DELETE CASCADE,
  vehicle_id varchar(60) REFERENCES agv(id),
  event_type varchar(60) NOT NULL,
  correlation_id varchar(120),
  vda_order_id varchar(120),
  order_update_id bigint NOT NULL DEFAULT 0,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  description text NOT NULL
);

CREATE INDEX execution_event_order_idx ON execution_event(transport_order_id, occurred_at);
CREATE INDEX execution_event_task_idx ON execution_event(transport_task_id, occurred_at);
CREATE INDEX execution_event_vehicle_idx ON execution_event(vehicle_id, occurred_at);
