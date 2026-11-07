import { get, put } from '@vercel/blob';
import { timingSafeEqual } from 'node:crypto';

const PATH = 'prices/latest.json';
const MAX_ITEMS = 2500;
const MAX_BYTES = 950_000;
function validToken(got, expected) {
  if (!expected || !got || !got.startsWith('Bearer ')) return false;
  const a = Buffer.from(got.slice(7));
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}
function sanitize(row) {
  if (!row || typeof row !== 'object') return null;
  const itemId = String(row.itemId || '').slice(0, 100);
  if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(itemId)) return null;
  const name = String(row.name || itemId).slice(0, 140).replace(/[<>\u0000-\u001f]/g, '');
  const perItem = String(row.perItem || '').slice(0, 30);
  const perStack = String(row.perStack || '').slice(0, 30);
  const price = String(row.price || perItem || perStack).slice(0, 30);
  if (![perItem, perStack, price].every(x => !x || /^[\d,.]+$/.test(x))) return null;
  if (!price) return null;
  return { itemId, name, perItem, perStack, price, updatedAt: new Date().toISOString() };
}
/**
 * Private write API. No Supabase service key or Blob secret is exposed to the mod.
 * The mod sends a whole snapshot at most once per ~30 seconds after updates.
 */
export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST'); return res.status(405).json({ error: 'Method not allowed' });
  }
  if (!process.env.PRICE_SYNC_TOKEN || !process.env.BLOB_READ_WRITE_TOKEN) {
    return res.status(503).json({ error: 'Sync not configured' });
  }
  if (!validToken(req.headers.authorization, process.env.PRICE_SYNC_TOKEN)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  const length = Number(req.headers['content-length'] || 0);
  if (length > MAX_BYTES) return res.status(413).json({ error: 'Payload too large' });
  let raw = req.body;
  try {
    if (typeof raw === 'string') raw = JSON.parse(raw);
    if (!raw || !Array.isArray(raw.items) || raw.items.length > MAX_ITEMS) {
      return res.status(400).json({ error: 'Invalid snapshot' });
    }
    const incoming = raw.items.map(sanitize).filter(Boolean);
    const merged = new Map();
    const previous = await get(PATH, { access: 'private', useCache: false });
    if (previous?.stream && previous.statusCode === 200) {
      try {
        const contents = JSON.parse(await new Response(previous.stream).text());
        for (const x of contents.items || []) {
          const item = sanitize(x);
          if (item) merged.set(item.itemId + '|' + item.name, item);
        }
      } catch (_) { /* An unreadable old snapshot never causes data loss: fail closed below. */ 
        return res.status(503).json({ error: 'Existing price snapshot unreadable' });
      }
    }
    for (const item of incoming) merged.set(item.itemId + '|' + item.name, item);
    if (merged.size > MAX_ITEMS) return res.status(422).json({ error: 'Item limit exceeded' });
    const updatedAt = new Date().toISOString();
    const value = JSON.stringify({ items: [...merged.values()], updatedAt });
    if (Buffer.byteLength(value, 'utf8') > MAX_BYTES) return res.status(413).json({ error: 'Snapshot too large' });
    await put(PATH, value, {
      access: 'private',
      allowOverwrite: true,
      addRandomSuffix: false,
      contentType: 'application/json',
      cacheControlMaxAge: 60,
    });
    return res.status(200).json({ ok: true, count: merged.size, updatedAt });
  } catch (error) {
    console.error('Price sync error:', error?.name || 'unknown');
    return res.status(503).json({ error: 'Unable to save prices' });
  }
}
