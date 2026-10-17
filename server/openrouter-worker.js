const DEFAULT_ALLOWED_MODELS = new Set([
  "google/gemini-3.8-flash",
  "openai/gpt-4o-mini",
  "deepseek/deepseek-chat",
  "qwen/qwen3-30b-a3b",
  "meta-llama/llama-3.3-70b-instruct",
]);

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Authorization, Content-Type",
      ...extraHeaders,
    },
  });
}

function decodeJwtPayload(token) {
  try {
    const part = token.split(".")[1];
    if (!part) return null;
    const normalized = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
    return JSON.parse(atob(padded));
  } catch (_) {
    return null;
  }
}

async function verifyFirebaseToken(token, env) {
  if (!env.FIREBASE_API_KEY || !env.FIREBASE_PROJECT_ID) {
    throw new Error("Worker chưa cấu hình Firebase.");
  }
  const payload = decodeJwtPayload(token);
  const now = Math.floor(Date.now() / 1000);
  if (!payload || payload.aud !== env.FIREBASE_PROJECT_ID ||
      payload.iss !== `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}` ||
      !payload.sub || !payload.exp || payload.exp <= now) {
    throw new Error("Firebase token không hợp lệ.");
  }

  const response = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(env.FIREBASE_API_KEY)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: token }),
    },
  );
  if (!response.ok) throw new Error("Không xác minh được phiên Firebase.");
  const result = await response.json();
  const user = result.users?.[0];
  if (!user || user.localId !== payload.sub || user.disabled === true) {
    throw new Error("Tài khoản Firebase không hợp lệ hoặc đã bị khóa.");
  }
}

async function readJsonWithLimit(request, maximumBytes) {
  if (!request.body) throw new Error("Thiếu nội dung yêu cầu.");
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maximumBytes) {
        try { await reader.cancel(); } catch (_) { }
        throw new Error("Yêu cầu quá lớn.");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const combined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { combined.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder().decode(combined));
}

function validateMessages(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 8) return null;
  const result = [];
  let totalChars = 0;
  for (const item of value) {
    if (!item || !["user", "assistant"].includes(item.role) || typeof item.content !== "string") return null;
    const content = item.content.trim();
    if (!content || content.length > 6000) return null;
    totalChars += content.length;
    if (totalChars > 18000) return null;
    result.push({ role: item.role, content });
  }
  if (!result.some((item) => item.role === "user")) return null;
  return result;
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return json({ ok: true });
    const url = new URL(request.url);
    if (url.pathname !== "/api/chat") return json({ ok: false, error: "Not found" }, 404);
    if (request.method !== "POST") return json({ ok: false, error: "Method not allowed" }, 405);
    if (!env.OPENROUTER_API_KEY) return json({ ok: false, error: "Máy chủ chưa cấu hình AI." }, 503);

    const authorization = request.headers.get("Authorization") || "";
    const tokenMatch = authorization.match(/^Bearer\s+(.+)$/i);
    if (!tokenMatch) return json({ ok: false, error: "Cần phiên đăng nhập hợp lệ." }, 401);

    let body;
    try {
      const length = Number(request.headers.get("Content-Length") || 0);
      if (length > 64_000) return json({ ok: false, error: "Yêu cầu quá lớn." }, 413);
      body = await readJsonWithLimit(request, 64_000);
    } catch (error) {
      const message = error instanceof Error ? error.message : "JSON không hợp lệ.";
      return json({ ok: false, error: message.includes("quá lớn") ? message : "JSON không hợp lệ." }, message.includes("quá lớn") ? 413 : 400);
    }

    const messages = validateMessages(body.messages);
    if (!messages) return json({ ok: false, error: "Danh sách tin nhắn không hợp lệ hoặc quá dài." }, 400);
    const allowedModels = env.ALLOWED_MODELS
      ? new Set(env.ALLOWED_MODELS.split(",").map((s) => s.trim()).filter(Boolean))
      : DEFAULT_ALLOWED_MODELS;
    const model = typeof body.model === "string" ? body.model.trim() : "";
    if (!allowedModels.has(model)) return json({ ok: false, error: "Mô hình này chưa được cho phép." }, 400);

    try {
      await verifyFirebaseToken(tokenMatch[1], env);
    } catch (_) {
      return json({ ok: false, error: "Phiên đăng nhập không hợp lệ. Hãy mở lại ứng dụng rồi thử lại." }, 401);
    }

    try {
      const upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${env.OPENROUTER_API_KEY}`,
          "Content-Type": "application/json",
          "HTTP-Referer": env.APP_SITE_URL || "https://digitalsmod.site",
          "X-Title": "DigitalLiquid AI",
        },
        body: JSON.stringify({
          model,
          messages,
          max_tokens: 1200,
          temperature: 0.65,
        }),
      });
      const raw = await upstream.text();
      if (!upstream.ok) {
        // Don't send raw upstream responses that may contain provider diagnostics to clients.
        return json({ ok: false, error: upstream.status === 429
          ? "AI đang bận hoặc đã chạm hạn mức. Thử lại sau."
          : "Nhà cung cấp AI chưa xử lý được yêu cầu." }, upstream.status === 429 ? 429 : 502);
      }
      let data;
      try { data = JSON.parse(raw); } catch (_) { return json({ ok: false, error: "Phản hồi AI không hợp lệ." }, 502); }
      const text = data.choices?.[0]?.message?.content;
      if (typeof text !== "string" || !text.trim()) return json({ ok: false, error: "AI không trả về nội dung." }, 502);
      return json({ ok: true, text: text.slice(0, 16_000) });
    } catch (_) {
      return json({ ok: false, error: "Không kết nối được nhà cung cấp AI." }, 502);
    }
  },
};
