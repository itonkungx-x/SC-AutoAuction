import { get } from '@vercel/blob';

/** Public read-only feed; the private Blob key never reaches the browser. */
export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'public, s-maxage=5, stale-while-revalidate=10');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET'); return res.status(405).json({ error: 'Method not allowed' });
  }
  if (!process.env.BLOB_READ_WRITE_TOKEN) {
    return res.status(503).json({ items: [], updatedAt: null, error: 'Storage not configured' });
  }
  try {
    const blob = await get('prices/latest.json', { access: 'private', useCache: false });
    if (!blob || !blob.stream || blob.statusCode !== 200) {
      return res.status(200).json({ items: [], updatedAt: null });
    }
    const body = JSON.parse(await new Response(blob.stream).text());
    if (!Array.isArray(body.items)) throw new Error('Malformed price snapshot');
    return res.status(200).json({ items: body.items, updatedAt: body.updatedAt });
  } catch (error) {
    console.error('Price read failed:', error?.name || 'unknown');
    return res.status(503).json({ items: [], updatedAt: null, error: 'Temporarily unavailable' });
  }
}
