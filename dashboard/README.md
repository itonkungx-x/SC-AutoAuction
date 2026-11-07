# Siamcraft Price Extractor — Vercel + Supabase Free

Source repository: https://github.com/itonkungx-x/Easylife
Production URL: https://siamcraft-price.vercel.app
Database: existing Supabase project \`Siamcraft-Priceitem\` (\`hakjebuugbklsfjjkzvn\`).

## Architecture
- Public GET \`/api/items\`: reads **public.siamcraft_item_prices** using Supabase's *publishable* key. Vercel edge cache is 60 seconds, stale cache up to 120 seconds.
- Authenticated POST \`/api/sync\`: validates \`PRICE_SYNC_TOKEN\`, forwards the snapshot to the deployed Supabase Edge Function \`siamcraft-price-ingest\`. The function checks the token SHA-256 against an RLS-protected auth table and invokes \`siamcraft_upsert_prices\` with its built-in service-role key. Only new/changed records are written.
- The Minecraft mod's \`config/easylife-remote.json\` remains compatible: \`{"url":"https://siamcraft-price.vercel.app/api/sync","token":"..."}\`. This file is local and private. Never commit it.
- The mod also maintains \`config/easylife-price-cache.json\` locally so the browser and cloud do not require Minecraft to be running.
- Price images are fetched by browser from an external item image provider and may fall back for custom items.

## Required Vercel environment
- \`SUPABASE_URL\`: https://hakjebuugbklsfjjkzvn.supabase.co
- \`SUPABASE_PUBLISHABLE_KEY\`: Supabase public publishable key (stored as Vercel encrypted environment variable).
- \`PRICE_SYNC_TOKEN\`: the existing private mod-to-API bearer token (stored as Vercel encrypted environment variable).

Do not add a Supabase service_role or other privileged API key to the public website or Minecraft mod.

## Storage migration
Vercel Blob is **not used** by these endpoints. The previous Blob store and token are deliberately not removed: inspect/migrate any old stored snapshot before deleting anything. Supabase Postgres is the only authoritative source for new price writes after cutover.

## Operational notes
- Supabase Free has a finite database and egress quota and can be paused for inactivity.
- Data writes only when a price changes or a new item appears.
- Vercel CDN caches public reads; the browser checks periodically but does not force a database query on every refresh.
- Never expose or print the auth token, any signed secret or privileged service-role key.
