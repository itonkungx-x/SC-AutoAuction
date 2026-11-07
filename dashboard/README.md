# Siamcraft Price Dashboard (Vercel)

This directory is the Vercel project root of the Easylife Git repository.

- `/` is the responsive price viewer. The list reads `/api/items` every 10 seconds while the browser is open.
- `GET /api/items` returns the last saved snapshot held in a **private Vercel Blob store**.
- `POST /api/sync` requires a server-side `PRICE_SYNC_TOKEN` and a valid bearer token. It validates and merges all current item prices rather than discarding previous scans.
- The project needs a private Blob store connected to Vercel with `BLOB_READ_WRITE_TOKEN` and one encrypted `PRICE_SYNC_TOKEN` environment secret. **Never commit credentials.**
- The client mod reads its own `config/easylife-remote.json` on the player's machine, containing `url` and `token`. This file must remain local, never committed.
- The website is a public **read-only** price display; secrets stay in Vercel serverless environment variables.
- Blob is persistent storage, but not a transactional database. Updates are batched. Avoid modifying prices concurrently from many clients without a conflict-control upgrade.
