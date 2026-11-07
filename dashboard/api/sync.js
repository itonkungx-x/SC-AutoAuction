import { timingSafeEqual } from 'node:crypto';

const MAX_BYTES = 950_000;
const MAX_ITEMS = 2500;
function verified(received, expected) {
  if (!expected || !received || !received.startsWith('Bearer ')) return false;
  const a = Buffer.from(received.slice(7));
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}
/**
 * Vercel remains the stable authenticated endpoint for all mod versions.
 * Supabase Edge Function authenticates the same key again before database writes.
 * No Supabase privileged service-role key reaches Vercel or the client.
 */
export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ error: 'Method not allowed' });
  }
  const token = process.env.PRICE_SYNC_TOKEN;
  const url = process.env.SUPABASE_URL;
  if (!token || !url) return res.status(503).json({ error: 'Sync not configured' });
  if (!verified(req.headers.authorization, token)) return res.status(401).json({ error: 'Unauthorized' });
  const length = Number(req.headers['content-length'] || 0);
  if (length < 0 || length > MAX_BYTES) return res.status(413).json({ error: 'Payload too large' });

  try {
    let data = req.body;
    if (typeof data === 'string') data = JSON.parse(data);
    if (!data || !Array.isArray(data.items) || data.items.length > MAX_ITEMS)
      return res.status(400).json({ error: 'Invalid items' });
    const payload = JSON.stringify({ items: data.items });
    if (Buffer.byteLength(payload, 'utf8') > MAX_BYTES)
      return res.status(413).json({ error: 'Payload too large' });
    const endpoint = new URL('/functions/v1/siamcraft-price-ingest', url);
    const result = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/json'
      },
      body: payload,
      signal: AbortSignal.timeout(20000)
    });
    if (!result.ok) {
      console.error('[Siamcraft] Price ingest failed with HTTP', result.status);
      return res.status(result.status === 401 ? 401 : 503)
        .json({ error: result.status === 401 ? 'Unauthorized' : 'Unable to save prices' });
    }
    const info = await result.json();
    return res.status(200).json({
      ok: info.ok === true,
      received: info.received,
      changed: info.changed,
      updatedAt: info.updatedAt
    });
  } catch (error) {
    console.error('[Siamcraft] Price sync failed', error instanceof Error ? error.name : 'unknown');
    return res.status(503).json({ error: 'Unable to save prices' });
  }
}
