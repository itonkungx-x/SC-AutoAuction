-- Siamcraft Price Extractor / Supabase project hakjebuugbklsfjjkzvn
-- Applied via Supabase migration. No tokens or privileged keys in source.
create table if not exists public.siamcraft_item_prices (
  item_id text not null,
  item_name text not null,
  per_item text not null default '',
  per_stack text not null default '',
  price_text text not null,
  updated_at timestamptz not null,
  synced_at timestamptz not null default now(),
  primary key(item_id, item_name)
);
alter table public.siamcraft_item_prices enable row level security;
revoke all on public.siamcraft_item_prices from anon, authenticated;
grant select on public.siamcraft_item_prices to anon, authenticated;
grant select, insert, update on public.siamcraft_item_prices to service_role;
create policy siamcraft_public_prices_read on public.siamcraft_item_prices
  for select to anon, authenticated using (true);
-- The dedicated sync token SHA-256 is stored in public.siamcraft_sync_auth.
-- That table has RLS and no anon/authenticated grants.
-- Its hash must never appear in a public API response.
-- The deployed Edge Function checks this hash using the service role and
-- then calls the service-role-only public.siamcraft_upsert_prices RPC.
