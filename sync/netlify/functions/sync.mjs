// Glorp's settings sync: keeps one encrypted blob per sync ID.
//
// The sync code never reaches this server. Glorp and the userscript derive
// from it an ID (the address here) and an AES key, encrypt the settings
// themselves and send only the ciphertext, so what's stored here can't be
// read by whoever runs it. Anyone who has the code can read and replace the
// settings; that's the whole of the access control, as with a shared secret.
//
//   GET /api/sync/<id>   -> 200 { data, at } or 404
//   PUT /api/sync/<id>   { data } -> 200 { at }
import { getStore } from "@netlify/blobs";

const ID = /^[0-9a-f]{32}$/;
const MAX_BYTES = 64 * 1024;

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

export default async (req, context) => {
  const id = context.params.id;
  if (!ID.test(id || "")) return json(400, { error: "bad id" });
  // Strong reads: a device reading right after another wrote gets what it wrote.
  const store = getStore({ name: "glorp-sync", consistency: "strong" });

  if (req.method === "GET") {
    const found = await store.get(id, { type: "json" });
    return found ? json(200, found) : json(404, { error: "none" });
  }

  if (req.method === "PUT") {
    const text = await req.text();
    if (text.length > MAX_BYTES) return json(413, { error: "too big" });
    let body;
    try { body = JSON.parse(text); } catch { return json(400, { error: "not json" }); }
    // Ciphertext only: base64 of the IV and the AES-GCM output.
    if (typeof body.data !== "string" || !/^[A-Za-z0-9+/=]+$/.test(body.data)) return json(400, { error: "bad data" });
    const at = Date.now();
    await store.setJSON(id, { data: body.data, at });
    return json(200, { at });
  }

  if (req.method === "DELETE") {
    await store.delete(id);
    return json(200, { deleted: true });
  }

  return json(405, { error: "method" });
};

export const config = { path: "/api/sync/:id" };
