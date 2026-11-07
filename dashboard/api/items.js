/** Public read-only feed of Siamcraft prices from Supabase PostgreSQL. */
export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'public, s-maxage=60, stale-while-revalidate=120');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET');
    return res.status(405).json({ error: 'Method not allowed' });
  }
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key) return res.status(503).json({ items: [], updatedAt: null, error: 'Database not configured' });
  try {
    const endpoint = new URL('/rest/v1/siamcraft_item_prices', url);
    endpoint.searchParams.set('select', 'item_id,item_name,per_item,per_stack,price_text,updated_at');
    endpoint.searchParams.set('order', 'item_id.asc,item_name.asc');
    endpoint.searchParams.set('limit', '2500');
    const upstream = await fetch(endpoint, {
      method: 'GET',
      headers: { 'apikey': key, 'Accept': 'application/json' },
      signal: AbortSignal.timeout(12000)
    });
    if (!upstream.ok) {
      console.error('[Siamcraft] Supabase read returned', upstream.status);
      return res.status(503).json({ items: [], updatedAt: null, error: 'Database temporarily unavailable' });
    }
    const rows = await upstream.json();
    if (!Array.isArray(rows)) throw new Error('Invalid Supabase response');
    const result = rows.map(row => ({
      itemId: row.item_id,
      name: row.item_name,
      perItem: row.per_item,
      perStack: row.per_stack,
      price: row.price_text,
      updatedAt: row.updated_at
    }));
    const newest = rows.reduce((last, row) => row.updated_at > last ? row.updated_at : last, '');
    return res.status(200).json({ items: result, updatedAt: newest || null });
  } catch (error) {
    console.error('[Siamcraft] Price read failed', error instanceof Error ? error.name : 'unknown');
    return res.status(503).json({ items: [], updatedAt: null, error: 'Database temporarily unavailable' });
  }
}
