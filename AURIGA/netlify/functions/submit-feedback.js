/*
 * Auriga feedback intake — Netlify Function.
 *
 * Pipeline (in order, every submission):
 *
 *   1. Validate payload. Bug + Support categories require a reply-to
 *      email; Idea + Other are optional. This is the "look professional"
 *      rule the operator picked.
 *
 *   2. Mint a short, human-readable ticket ID (e.g. AUR-BUG-7K2D) so the
 *      operator and the user can quote the same reference in any future
 *      reply thread.
 *
 *   3. Send a notification email to the operator's Gmail via SMTP
 *      (Nodemailer), with the user's email set as Reply-To so a reply
 *      from Gmail goes back to them — not to the project address.
 *
 *   4. If the user supplied an email, send them an immediate, branded
 *      auto-acknowledgement quoting the ticket ID. Failure here is
 *      logged but does NOT fail the request.
 *
 *   5. Mirror the submission as a GitHub issue (auto-labelled by
 *      category) so the repo's Issues tab is the long-term ledger.
 *      Failure here is also logged but does NOT fail the request — the
 *      user-visible promise is "we received your message", which is
 *      true the moment the operator email is sent.
 *
 * All credentials are read from environment variables, never from the
 * repo. Required env vars (set in the Netlify UI):
 *
 *   GMAIL_USER             – e.g. drakosanctis@gmail.com
 *   GMAIL_APP_PASSWORD     – 16-char Google App Password (no spaces)
 *
 * Optional env vars:
 *
 *   GMAIL_FROM_NAME        – display name for outgoing mail
 *                            (defaults to "DrakoSanctis Auriga")
 *   FEEDBACK_GITHUB_REPO   – owner/repo, e.g. michaelomosh118-tech/AURIGA
 *   FEEDBACK_GITHUB_TOKEN  – fine-grained PAT with Issues: write
 *   FEEDBACK_FORWARD_WEBHOOK – generic POST receiver (Slack/Discord/Zapier)
 *
 * If GMAIL_USER / GMAIL_APP_PASSWORD are missing the function still
 * returns 200 (so the client falls through to its own queue/mailto
 * fallback) but logs a loud warning so the operator notices.
 */

const nodemailer = require('nodemailer');

const REQUIRE_EMAIL_FOR = new Set(['bug', 'support']);

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
    return jsonResponse(400, cors, { ok: false, error: 'Invalid JSON' });
  }

  const validation = validatePayload(payload);
  if (!validation.ok) {
    return jsonResponse(422, cors, { ok: false, error: validation.error });
  }

  const ticketId = mintTicketId(payload.category);
  payload.ticketId = ticketId;
  console.log('[auriga-feedback]', ticketId, renderForLogs(payload));

  // ── 1. Operator notification email (the source-of-truth step) ─────
  const mailResult = await sendOperatorEmail(payload, ticketId);
  // ── 2. User auto-acknowledgement (best-effort) ────────────────────
  let ackResult = { skipped: true };
  if (payload.email) {
    ackResult = await sendUserAck(payload, ticketId);
  }
  // ── 3. GitHub issue mirror (best-effort) ──────────────────────────
  const githubResult = await fileGithubIssue(payload, ticketId);
  // ── 4. Optional generic webhook (best-effort) ─────────────────────
  const webhookResult = await forwardToWebhook(payload, ticketId);

  // We only fail the request if the operator email itself failed
  // AND no other sink succeeded — otherwise the user gets a clean
  // success and we have a paper trail somewhere.
  const anySinkOk =
        mailResult.ok || githubResult.ok || webhookResult.ok;
  const status = anySinkOk ? 200 : 502;

  return jsonResponse(status, cors, {
    ok: anySinkOk,
    ticketId,
    received: anySinkOk,
    sinks: {
      operatorEmail: summariseSink(mailResult),
      userAck:       summariseSink(ackResult),
      github:        summariseSink(githubResult),
      webhook:       summariseSink(webhookResult),
    },
  });
};

// ──────────────────────────────────────────────────────────────────────
// Validation
// ──────────────────────────────────────────────────────────────────────
function validatePayload(p) {
  const message = (p.message || '').trim();
  if (!message || message.length < 5) return { ok: false, error: 'message too short' };
  if (message.length > 8000)           return { ok: false, error: 'message too long' };

  const category = (p.category || 'other').toLowerCase();
  const email    = (p.email || '').trim();
  if (REQUIRE_EMAIL_FOR.has(category) && !email) {
    return { ok: false, error: `email is required for ${category} reports` };
  }
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return { ok: false, error: 'reply-to email looks malformed' };
  }
  return { ok: true };
}

// ──────────────────────────────────────────────────────────────────────
// Ticket IDs — short, unambiguous, alphanumeric (no I/O/0/1).
// Format: AUR-<3-letter category>-<4-char base32 random>
// e.g. AUR-BUG-7K2D, AUR-IDE-Q4XR.
// ──────────────────────────────────────────────────────────────────────
function mintTicketId(category) {
  const c = (category || 'other').toUpperCase().slice(0, 3).padEnd(3, 'X');
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  for (let i = 0; i < 4; i++) {
    s += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return `AUR-${c}-${s}`;
}

// ──────────────────────────────────────────────────────────────────────
// Gmail SMTP (Nodemailer)
// ──────────────────────────────────────────────────────────────────────
function buildTransport() {
  const user = process.env.GMAIL_USER;
  const pass = process.env.GMAIL_APP_PASSWORD;
  if (!user || !pass) {
    console.warn('[auriga-feedback] GMAIL_USER / GMAIL_APP_PASSWORD not set — operator email is disabled.');
    return null;
  }
  return nodemailer.createTransport({
    host: 'smtp.gmail.com',
    port: 465,
    secure: true,
    auth: { user, pass: pass.replace(/\s+/g, '') },
  });
}

async function sendOperatorEmail(p, ticketId) {
  const transport = buildTransport();
  if (!transport) return { ok: false, skipped: true, error: 'gmail-not-configured' };

  const fromName = process.env.GMAIL_FROM_NAME || 'DrakoSanctis Auriga';
  const user = process.env.GMAIL_USER;
  const subjectTag = `[AURIGA · ${(p.category || 'OTHER').toUpperCase()}]`;
  const subject = `${subjectTag} ${ticketId} — ${oneLineSummary(p.message)}`;

  const text = renderOperatorEmailText(p, ticketId);
  const html = renderOperatorEmailHtml(p, ticketId);

  try {
    const info = await transport.sendMail({
      from: `"${fromName}" <${user}>`,
      to: user,
      replyTo: p.email || undefined,
      subject,
      text,
      html,
      headers: {
        'X-Auriga-Ticket': ticketId,
        'X-Auriga-Category': p.category || 'other',
        'X-Auriga-Product': p.product || 'unknown',
      },
    });
    return { ok: true, messageId: info.messageId };
  } catch (err) {
    console.error('[auriga-feedback] operator email failed', err && err.message);
    return { ok: false, error: err && err.message };
  }
}

async function sendUserAck(p, ticketId) {
  const transport = buildTransport();
  if (!transport) return { ok: false, skipped: true, error: 'gmail-not-configured' };

  const fromName = process.env.GMAIL_FROM_NAME || 'DrakoSanctis Auriga';
  const user = process.env.GMAIL_USER;
  const subject = `Auriga · we received your ${p.category || 'message'} (${ticketId})`;
  const text = renderAckText(p, ticketId, user);
  const html = renderAckHtml(p, ticketId, user);
  try {
    const info = await transport.sendMail({
      from: `"${fromName}" <${user}>`,
      to: p.email,
      replyTo: user,
      subject,
      text,
      html,
      headers: { 'X-Auriga-Ticket': ticketId },
    });
    return { ok: true, messageId: info.messageId };
  } catch (err) {
    console.warn('[auriga-feedback] user ack failed', err && err.message);
    return { ok: false, error: err && err.message };
  }
}

// ──────────────────────────────────────────────────────────────────────
// GitHub mirror
// ──────────────────────────────────────────────────────────────────────
async function fileGithubIssue(p, ticketId) {
  const repo  = process.env.FEEDBACK_GITHUB_REPO;
  const token = process.env.FEEDBACK_GITHUB_TOKEN;
  if (!repo || !token) return { ok: false, skipped: true, error: 'github-not-configured' };
  try {
    const title = `${ticketId} · [${(p.category || 'other').toUpperCase()}] ${oneLineSummary(p.message)}`;
    const body = renderGithubBody(p, ticketId);
    const res = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'auriga-feedback-fn',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      body: JSON.stringify({
        title,
        body,
        labels: ['user-feedback', `cat:${(p.category || 'other').toLowerCase()}`],
      }),
    });
    if (!res.ok) {
      const t = await res.text();
      console.warn('[auriga-feedback] github failed', res.status, t.slice(0, 400));
      return { ok: false, error: `HTTP ${res.status}` };
    }
    const json = await res.json();
    return { ok: true, issueUrl: json.html_url, issueNumber: json.number };
  } catch (err) {
    console.warn('[auriga-feedback] github threw', err && err.message);
    return { ok: false, error: err && err.message };
  }
}

async function forwardToWebhook(p, ticketId) {
  const webhook = process.env.FEEDBACK_FORWARD_WEBHOOK;
  if (!webhook) return { ok: false, skipped: true, error: 'webhook-not-configured' };
  try {
    const res = await fetch(webhook, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ticketId, summary: renderForLogs(p), payload: p }),
    });
    return { ok: res.ok };
  } catch (err) {
    return { ok: false, error: err && err.message };
  }
}

// ──────────────────────────────────────────────────────────────────────
// Rendering helpers
// ──────────────────────────────────────────────────────────────────────
function oneLineSummary(message) {
  const m = (message || '').replace(/\s+/g, ' ').trim();
  return m.length > 70 ? m.slice(0, 67) + '…' : m;
}

function renderOperatorEmailText(p, ticketId) {
  return [
    `Auriga ticket ${ticketId}`,
    `Category: ${p.category || 'other'}`,
    `Reply-to: ${p.email || '(not provided)'}`,
    `Product:  ${p.product || '?'}`,
    `Version:  ${p.version || '?'}`,
    `Device:   ${p.device || '?'}`,
    `Profile:  ${p.profile || '(unknown)'}`,
    `When:     ${p.ts ? new Date(p.ts).toISOString() : new Date().toISOString()}`,
    '',
    '── Message ──',
    p.message || '',
    '',
    '── Diagnostic snapshot ──',
    (p.diagnostic || '(none)'),
    '',
    `(Tip: hit Reply in Gmail — it will go to ${p.email || 'the user'} thanks to Reply-To.)`,
  ].join('\n');
}

function renderOperatorEmailHtml(p, ticketId) {
  const safe = (s) => String(s || '').replace(/[&<>"']/g,
      (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  return `
<!doctype html><html><body style="font-family:Inter,Segoe UI,system-ui,sans-serif;background:#05060A;color:#FFF;padding:24px;">
  <div style="max-width:640px;margin:0 auto;background:rgba(0,240,255,0.04);border:1px solid rgba(0,240,255,0.20);border-radius:14px;padding:22px;">
    <div style="font-size:11px;letter-spacing:0.18em;text-transform:uppercase;color:#7AA3C8;">DrakoSanctis · Auriga</div>
    <h2 style="color:#00F0FF;margin:6px 0 14px;font-weight:700;letter-spacing:0.04em;">Ticket ${safe(ticketId)} · ${safe((p.category || 'other').toUpperCase())}</h2>
    <table cellspacing="0" cellpadding="6" style="width:100%;border-collapse:collapse;font-size:13px;color:#CDE7F2;">
      <tr><td style="opacity:0.7;width:120px;">Reply-to</td><td>${safe(p.email || '(not provided)')}</td></tr>
      <tr><td style="opacity:0.7;">Product</td><td>${safe(p.product || '?')}</td></tr>
      <tr><td style="opacity:0.7;">Version</td><td>${safe(p.version || '?')}</td></tr>
      <tr><td style="opacity:0.7;">Device</td><td>${safe(p.device || '?')}</td></tr>
      <tr><td style="opacity:0.7;">Profile</td><td>${safe(p.profile || '(unknown)')}</td></tr>
      <tr><td style="opacity:0.7;">When</td><td>${safe(p.ts ? new Date(p.ts).toISOString() : new Date().toISOString())}</td></tr>
    </table>
    <div style="margin-top:18px;font-size:11px;letter-spacing:0.16em;text-transform:uppercase;color:#00F0FF;">Message</div>
    <div style="background:rgba(0,0,0,0.35);border:1px solid rgba(0,240,255,0.15);border-radius:10px;padding:14px;margin-top:6px;white-space:pre-wrap;line-height:1.55;">${safe(p.message || '')}</div>
    <div style="margin-top:18px;font-size:11px;letter-spacing:0.16em;text-transform:uppercase;color:#00F0FF;">Diagnostic snapshot</div>
    <pre style="background:rgba(0,0,0,0.45);border:1px solid rgba(0,240,255,0.15);border-radius:10px;padding:14px;margin-top:6px;white-space:pre-wrap;line-height:1.5;font-size:12px;color:#CDE7F2;">${safe(p.diagnostic || '(none)')}</pre>
    <p style="margin-top:18px;font-size:12px;color:#7AA3C8;">Tip: hit Reply in Gmail — it goes to <b>${safe(p.email || 'the user')}</b> thanks to Reply-To.</p>
  </div>
</body></html>`;
}

function renderAckText(p, ticketId, projectAddress) {
  return [
    `Hi,`,
    ``,
    `Thanks for reaching out about Auriga. Your message has been received and assigned ticket ${ticketId}.`,
    ``,
    `We read every submission. If we need more detail we'll reply to this address within a couple of days.`,
    ``,
    `Quick reference:`,
    `  Ticket:   ${ticketId}`,
    `  Category: ${p.category || 'other'}`,
    `  Logged:   ${p.ts ? new Date(p.ts).toISOString() : new Date().toISOString()}`,
    ``,
    `If you need to add anything, just reply to this email — your reply will be threaded onto the ticket automatically.`,
    ``,
    `— DrakoSanctis · Auriga`,
    `   ${projectAddress}`,
  ].join('\n');
}

function renderAckHtml(p, ticketId, projectAddress) {
  const safe = (s) => String(s || '').replace(/[&<>"']/g,
      (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  return `
<!doctype html><html><body style="font-family:Inter,Segoe UI,system-ui,sans-serif;background:#05060A;color:#FFF;padding:24px;">
  <div style="max-width:560px;margin:0 auto;background:rgba(0,240,255,0.04);border:1px solid rgba(0,240,255,0.20);border-radius:14px;padding:24px;">
    <div style="font-size:11px;letter-spacing:0.18em;text-transform:uppercase;color:#7AA3C8;">DrakoSanctis · Auriga</div>
    <h2 style="color:#00F0FF;margin:6px 0 14px;font-weight:700;letter-spacing:0.04em;">We received your message</h2>
    <p style="line-height:1.6;color:#CDE7F2;">Thanks for reaching out. Your submission has been logged as ticket
      <span style="font-family:monospace;background:rgba(0,240,255,0.12);padding:2px 8px;border-radius:6px;color:#00F0FF;">${safe(ticketId)}</span>.</p>
    <p style="line-height:1.6;color:#CDE7F2;">We read every submission. If we need more detail we'll reply to this address within a couple of days. If you'd like to add anything, just reply to this email and the new content will be threaded onto the ticket automatically.</p>
    <table cellspacing="0" cellpadding="6" style="width:100%;border-collapse:collapse;font-size:13px;color:#CDE7F2;margin-top:12px;">
      <tr><td style="opacity:0.7;width:110px;">Ticket</td><td style="font-family:monospace;color:#00F0FF;">${safe(ticketId)}</td></tr>
      <tr><td style="opacity:0.7;">Category</td><td>${safe(p.category || 'other')}</td></tr>
      <tr><td style="opacity:0.7;">Logged</td><td>${safe(p.ts ? new Date(p.ts).toISOString() : new Date().toISOString())}</td></tr>
    </table>
    <p style="margin-top:22px;font-size:12px;color:#7AA3C8;">— DrakoSanctis · Auriga<br/><span style="font-family:monospace;">${safe(projectAddress)}</span></p>
  </div>
</body></html>`;
}

function renderGithubBody(p, ticketId) {
  return [
    `**Ticket:** \`${ticketId}\``,
    '',
    `> ${(p.message || '').replace(/\r?\n/g, '\n> ')}`,
    '',
    '| field | value |',
    '| --- | --- |',
    `| category | \`${p.category || 'other'}\` |`,
    `| product  | \`${p.product || '?'}\` |`,
    `| version  | \`${p.version || '?'}\` |`,
    `| device   | \`${p.device || '?'}\` |`,
    `| profile  | \`${p.profile || '(unknown)'}\` |`,
    `| email    | ${p.email ? '`' + p.email + '`' : '_not provided_'} |`,
    `| ts       | ${p.ts ? new Date(p.ts).toISOString() : new Date().toISOString()} |`,
    '',
    '### Diagnostic snapshot',
    '```',
    (p.diagnostic || '(none)').slice(0, 4000),
    '```',
    '',
    `_Auto-filed by the Auriga in-app feedback button. Operator notification email also sent to the project Gmail._`,
  ].join('\n');
}

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

function summariseSink(r) {
  if (!r) return { ok: false };
  if (r.skipped) return { skipped: true, reason: r.error };
  if (r.ok) {
    if (r.issueUrl) return { ok: true, issueUrl: r.issueUrl, issueNumber: r.issueNumber };
    if (r.messageId) return { ok: true };
    return { ok: true };
  }
  return { ok: false, error: r.error };
}

function jsonResponse(statusCode, cors, body) {
  return {
    statusCode,
    headers: { 'Content-Type': 'application/json', ...cors },
    body: JSON.stringify(body),
  };
}
