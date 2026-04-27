const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

// We re-use the exact Netlify Function handler so dev (Replit) and
// production (Netlify) run identical code. Anything you change in
// AURIGA/netlify/functions/submit-feedback.js is immediately picked
// up by `npm start` here too — single source of truth.
const feedbackFn = require('./AURIGA/netlify/functions/submit-feedback.js');

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

// Adapter: reshape a Node http req into the Netlify-Function event
// envelope, invoke the shared handler, and pipe its response back.
async function handleFeedback(req, res) {
  try {
    const body = await collectBody(req);
    const result = await feedbackFn.handler({
      httpMethod: req.method,
      headers: req.headers,
      body,
    });
    res.writeHead(result.statusCode || 200, result.headers || {});
    res.end(result.body || '');
  } catch (err) {
    console.error('[server] feedback handler crashed', err);
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: 'internal' }));
  }
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
