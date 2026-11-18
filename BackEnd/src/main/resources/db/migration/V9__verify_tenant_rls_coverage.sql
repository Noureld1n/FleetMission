DO $$
DECLARE missing_table text;
BEGIN
    SELECT format('%I.%I', n.nspname, c.relname)
      INTO missing_table
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND a.attnum > 0 AND NOT a.attisdropped
       AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
            OR NOT EXISTS (
                SELECT 1 FROM pg_policy p
                 WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation'
            ))
     LIMIT 1;

    IF missing_table IS NOT NULL THEN
        RAISE EXCEPTION 'Tenant table % lacks enabled/forced RLS or tenant_isolation policy', missing_table;
    END IF;
END $$;
