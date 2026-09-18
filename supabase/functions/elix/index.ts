// Elix — the PhoneXR assistant. Runs as a Supabase Edge Function so the Anthropic API key stays on
// the server. Deploy:  supabase functions deploy elix
//          then:       supabase secrets set ANTHROPIC_API_KEY=sk-ant-...
// Only signed-in PhoneXR users can call it (the function checks the user's JWT by default).

const MODEL = "claude-sonnet-5";
const MAX_TURNS = 20;

const PROMPTS: Record<string, string> = {
  ru: "Ты Elix — ассистент в VR-шлеме PhoneXR. Отвечай кратко и по делу, обычным текстом без markdown: " +
    "ответ читают на панели в виртуальной реальности. Помогай с PhoneXR (VR-дом, звонки персонами, магазин, " +
    "граница комнаты, 6DoF) и с любыми вопросами.",
  en: "You are Elix, the assistant in the PhoneXR VR headset. Answer briefly, in plain text without markdown: " +
    "the answer is read on a panel in VR. Help with PhoneXR (VR home, Persona calls, store, room boundary, 6DoF) " +
    "and with any question.",
  "pt-BR": "Você é Elix, a assistente do headset VR PhoneXR. Responda de forma breve, em texto simples sem markdown: " +
    "a resposta é lida num painel em VR. Ajude com o PhoneXR e com qualquer pergunta.",
  "pt-PT": "É a Elix, a assistente do headset VR PhoneXR. Responda de forma breve, em texto simples sem markdown: " +
    "a resposta é lida num painel em VR. Ajude com o PhoneXR e com qualquer pergunta.",
};

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response(null, { headers: cors });
  const key = Deno.env.get("ANTHROPIC_API_KEY");
  if (!key) return json({ error: "ANTHROPIC_API_KEY is not set" }, 500);

  let body: { messages?: { role: string; content: string }[]; language?: string };
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad request" }, 400);
  }
  const messages = (body.messages ?? [])
    .filter((m) => (m.role === "user" || m.role === "assistant") && typeof m.content === "string" && m.content.trim())
    .map((m) => ({ role: m.role, content: m.content.slice(0, 4000) }))
    .slice(-MAX_TURNS);
  if (messages.length === 0 || messages[messages.length - 1].role !== "user") return json({ error: "no question" }, 400);

  const answer = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 700,
      system: PROMPTS[body.language ?? "ru"] ?? PROMPTS.ru,
      messages,
    }),
  });
  const data = await answer.json();
  if (!answer.ok) return json({ error: data?.error?.message ?? "Anthropic error" }, 502);
  const text = (data.content ?? []).filter((b: { type: string }) => b.type === "text").map((b: { text: string }) => b.text).join("");
  return json({ text });
});
