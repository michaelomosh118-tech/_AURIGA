#!/usr/bin/env node
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const WATCH_DIRS = [
  path.join(ROOT, 'AURIGA', 'web_deploy'),
  path.join(ROOT, 'AURIGA', 'netlify', 'functions'),
];

const DEBOUNCE_MS = parseInt(process.env.NETLIFY_WATCH_DEBOUNCE_MS || '4000', 10);

function info(msg) {
  console.log(`\x1b[35m[watch]\x1b[0m ${msg}`);
}

let pending = null;
let deploying = false;
let queued = false;

function runDeploy() {
  if (deploying) {
    queued = true;
    info('Deploy already in progress — queuing another run.');
    return;
  }
  deploying = true;
  info('Triggering Netlify deploy...');
  const child = spawn('node', [path.join(__dirname, 'deploy-netlify.js')], {
    stdio: 'inherit',
    env: process.env,
  });
  child.on('exit', (code) => {
    deploying = false;
    info(code === 0 ? 'Deploy succeeded.' : `Deploy failed (exit ${code}).`);
    if (queued) {
      queued = false;
      info('Running queued deploy...');
      runDeploy();
    }
  });
}

function schedule(reason) {
  if (pending) clearTimeout(pending);
  pending = setTimeout(() => {
    pending = null;
    info(`Debounce elapsed (${reason}).`);
    runDeploy();
  }, DEBOUNCE_MS);
}

for (const dir of WATCH_DIRS) {
  if (!fs.existsSync(dir)) {
    info(`Skipping (not found): ${dir}`);
    continue;
  }
  info(`Watching: ${dir}`);
  fs.watch(dir, { recursive: true }, (event, filename) => {
    if (!filename) return;
    // Ignore editor swap / temp files
    if (/(\.swp|~$|\.tmp$|\.DS_Store)/.test(filename)) return;
    schedule(`${event}: ${filename}`);
  });
}

info(`Auto-deploy armed. Debounce = ${DEBOUNCE_MS}ms.`);
info('Edit any file under AURIGA/web_deploy or AURIGA/netlify/functions to trigger a production deploy.');
