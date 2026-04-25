const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = process.env.PORT || 5000;
const STATIC_DIR = path.join(__dirname, 'AURIGA', 'web_deploy');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

function collectBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk.toString(); });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

async function handleFeedback(req, res) {
  const cors = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };

  if (req.method === 'OPTIONS') {
    res.writeHead(204, cors);
    res.end();
    return;
  }

  if (req.method !== 'POST') {
    res.writeHead(405, cors);
    res.end('Method Not Allowed');
    return;
  }

  let payload;
  try {
    const body = await collectBody(req);
    payload = JSON.parse(body || '{}');
  } catch (e) {
    res.writeHead(400, { 'Content-Type': 'application/json', ...cors });
    res.end(JSON.stringify({ ok: false, error: 'Invalid JSON' }));
    return;
  }

  const message = (payload.message || '').trim();
  if (!message || message.length < 5) {
    res.writeHead(422, { 'Content-Type': 'application/json', ...cors });
    res.end(JSON.stringify({ ok: false, error: 'message too short' }));
    return;
  }
  if (message.length > 8000) {
    res.writeHead(422, { 'Content-Type': 'application/json', ...cors });
    res.end(JSON.stringify({ ok: false, error: 'message too long' }));
    return;
  }

  const summary = [
    `cat=${payload.category || 'other'}`,
    `prod=${payload.product || '?'}`,
    `ver=${payload.version || '?'}`,
    `dev=${(payload.device || '?').replace(/\s+/g, '_')}`,
    `email=${payload.email || ''}`,
    `msg=${(payload.message || '').slice(0, 400).replace(/\s+/g, ' ')}`,
  ].join(' | ');

  console.log('[auriga-feedback]', summary);

  let webhookOk = null;
  const webhook = process.env.FEEDBACK_FORWARD_WEBHOOK;
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

  let ghOk = null;
  const ghRepo = process.env.FEEDBACK_GITHUB_REPO;
  const ghToken = process.env.FEEDBACK_GITHUB_TOKEN;
  if (ghRepo && ghToken) {
    try {
      const title = `[${payload.category || 'feedback'}] ${message.slice(0, 60).replace(/\s+/g, ' ')}…`;
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
          labels: ['user-feedback', `cat:${payload.category || 'other'}`],
        }),
      });
      ghOk = r.ok;
    } catch (e) {
      console.warn('[auriga-feedback] github threw', e && e.message);
      ghOk = false;
    }
  }

  res.writeHead(200, { 'Content-Type': 'application/json', ...cors });
  res.end(JSON.stringify({ ok: true, received: true, forwarded: { webhook: webhookOk, github: ghOk } }));
}

const server = http.createServer(async (req, res) => {
  const parsedUrl = url.parse(req.url);
  const pathname = parsedUrl.pathname;

  if (pathname === '/.netlify/functions/submit-feedback' || pathname === '/api/submit-feedback') {
    return handleFeedback(req, res);
  }

  let filePath = path.join(STATIC_DIR, pathname === '/' ? 'index.html' : pathname);

  if (!filePath.startsWith(STATIC_DIR)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }

  if (fs.existsSync(filePath) && fs.statSync(filePath).isDirectory()) {
    filePath = path.join(filePath, 'index.html');
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Auriga server running on http://0.0.0.0:${PORT}`);
});
