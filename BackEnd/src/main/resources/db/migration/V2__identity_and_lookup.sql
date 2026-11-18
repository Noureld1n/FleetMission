CREATE TABLE public.tenants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    slug text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    email citext NOT NULL UNIQUE,
    password_hash text NOT NULL,
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    role text NOT NULL CHECK (role IN ('operator', 'viewer')),
    created_at timestamptz NOT NULL DEFAULT now(),
    disabled_at timestamptz,
    UNIQUE (tenant_id, id)
);

CREATE TABLE public.api_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    key_prefix text NOT NULL UNIQUE,
    key_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at timestamptz CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['users','api_keys'] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON public.users, public.api_keys TO app_runtime;
GRANT SELECT (id, tenant_id, email, password_hash, role, disabled_at) ON public.users TO app_lookup;
GRANT SELECT (id, tenant_id, key_prefix, key_hash, revoked_at) ON public.api_keys TO app_lookup;

SET ROLE app_lookup;
CREATE FUNCTION app.login_lookup(p_email citext)
RETURNS TABLE (user_id uuid, tenant_id uuid, password_hash text, role text, disabled_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ SELECT u.id, u.tenant_id, u.password_hash, u.role, u.disabled_at
      FROM public.users AS u WHERE u.email = p_email $$;
CREATE FUNCTION app.api_key_lookup(p_prefix text)
RETURNS TABLE (key_id uuid, tenant_id uuid, key_hash text, revoked_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ SELECT k.id, k.tenant_id, k.key_hash, k.revoked_at
      FROM public.api_keys AS k WHERE k.key_prefix = p_prefix $$;
RESET ROLE;

REVOKE ALL ON FUNCTION app.login_lookup(citext) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.api_key_lookup(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.login_lookup(citext), app.api_key_lookup(text) TO app_runtime;
