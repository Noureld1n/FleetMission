-- Persist the timestamp used by SSE event IDs and Last-Event-ID replay.
ALTER TABLE public.alerts
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE public.alerts
    ADD CONSTRAINT alerts_updated_at_after_opened_check CHECK (updated_at >= opened_at);

CREATE INDEX alerts_tenant_updated_at_idx ON public.alerts (tenant_id, updated_at, id);
