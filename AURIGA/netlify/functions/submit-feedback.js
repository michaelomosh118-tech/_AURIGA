/*
 * Auriga feedback intake — Netlify Function (D1 = C).
 *
 * Accepts JSON-encoded POSTs from the AurigaNavi Android app
 * (and the web reader, future) and forwards them to whichever sink
 * the operator has configured via environment variables:
 *
 *   FEEDBACK_FORWARD_WEBHOOK   – generic POST receiver (Slack/Discord/Zapier)
 *   FEEDBACK_GITHUB_REPO       – e.g. "michaelomosh118-tech/_AURIGA"
 *   FEEDBACK_GITHUB_TOKEN      – a fine-grained PAT with issues:write
 *
 * If neither sink is configured the function still returns 200 so the
 * client gets a usable success path (and the Netlify function logs hold
 * the payload for manual review). This matches the agent's "C with B as
 * fallback" recommendation — Netlify Forms can be wired by changing the
 * client to POST to /__forms.html instead of this endpoint.
 */
exports.handler = async (event) => {
  const cors = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };

  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 204, headers: cors, body: '' };
  }
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, headers: cors, body: 'Method Not Allowed' };
  }

  let payload;
  try {
    payload = JSON.parse(event.body || '{}');
  } catch (e) {
    return {
      statusCode: 400,
      headers: cors,
      body: JSON.stringify({ ok: false, error: 'Invalid JSON' }),
    };
  }

  // Server-side validation. Keep it permissive — this is operator-facing
  // feedback, not user-facing input — but reject blank/garbage payloads
  // so we don't fill the GitHub issue tracker with empty rows.
  const message = (payload.message || '').trim();
  if (!message || message.length < 5) {
    return {
      statusCode: 422,
      headers: cors,
      body: JSON.stringify({ ok: false, error: 'message too short' }),
    };
  }
  if (message.length > 8000) {
    return {
      statusCode: 422,
      headers: cors,
      body: JSON.stringify({ ok: false, error: 'message too long' }),
    };
  }

  const summary = renderForLogs(payload);
  console.log('[auriga-feedback]', summary);

  // ── Optional: forward to a generic webhook ──────────────────────
  const webhook = process.env.FEEDBACK_FORWARD_WEBHOOK;
  let webhookOk = null;
  if (webhook) {
    try {
      const r = await fetch(webhook, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: summary, payload }),
      });
      webhookOk = r.ok;
    } catch (e) {
      console.warn('[auriga-feedback] webhook failed', e && e.message);
      webhookOk = false;
    }
  }

  // ── Optional: file as a GitHub issue ────────────────────────────
  const ghRepo = process.env.FEEDBACK_GITHUB_REPO;
  const ghToken = process.env.FEEDBACK_GITHUB_TOKEN;
  let ghOk = null;
  if (ghRepo && ghToken) {
    try {
      const title = `[${payload.category || 'feedback'}] ${message.slice(0, 60).replace(/\s+/g, ' ')}…`;
      const body = renderForGithub(payload);
      const r = await fetch(`https://api.github.com/repos/${ghRepo}/issues`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${ghToken}`,
          'Accept': 'application/vnd.github+json',
          'User-Agent': 'auriga-feedback-fn',
          'X-GitHub-Api-Version': '2022-11-28',
        },
        body: JSON.stringify({
          title,
          body,
          labels: ['user-feedback', `cat:${payload.category || 'other'}`],
        }),
      });
      ghOk = r.ok;
      if (!r.ok) {
        const t = await r.text();
        console.warn('[auriga-feedback] github failed', r.status, t.slice(0, 400));
      }
    } catch (e) {
      console.warn('[auriga-feedback] github threw', e && e.message);
      ghOk = false;
    }
  }

  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json', ...cors },
    body: JSON.stringify({
      ok: true,
      received: true,
      forwarded: { webhook: webhookOk, github: ghOk },
    }),
  };
};

function renderForLogs(p) {
  return [
    `cat=${p.category || 'other'}`,
    `prod=${p.product || '?'}`,
    `ver=${p.version || '?'}`,
    `dev=${(p.device || '?').replace(/\s+/g, '_')}`,
    `email=${p.email || ''}`,
    `msg=${(p.message || '').slice(0, 400).replace(/\s+/g, ' ')}`,
  ].join(' | ');
}

function renderForGithub(p) {
  return [
    `**${p.message || ''}**`,
    '',
    '| field | value |',
    '| --- | --- |',
    `| category | \`${p.category || 'other'}\` |`,
    `| product  | \`${p.product || '?'}\` |`,
    `| version  | \`${p.version || '?'}\` |`,
    `| device   | \`${p.device || '?'}\` |`,
    `| profile  | \`${p.profile || '(unknown)'}\` |`,
    `| email    | ${p.email ? '`' + p.email + '`' : '_not provided_'} |`,
    `| ts       | ${p.ts ? new Date(p.ts).toISOString() : '?'} |`,
    '',
    '### Diagnostic snapshot',
    '```',
    (p.diagnostic || '(none)').slice(0, 2000),
    '```',
    '',
    '_Auto-filed by the Auriga in-app feedback button._',
  ].join('\n');
}
