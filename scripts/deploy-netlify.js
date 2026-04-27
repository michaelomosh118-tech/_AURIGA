#!/usr/bin/env node
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const SITE_DIR = path.join(__dirname, '..', 'AURIGA', 'web_deploy');
const FUNCTIONS_DIR = path.join(__dirname, '..', 'AURIGA', 'netlify', 'functions');

const isDraft = process.argv.includes('--draft');

function fail(msg) {
  console.error(`\x1b[31m[deploy]\x1b[0m ${msg}`);
  process.exit(1);
}

function info(msg) {
  console.log(`\x1b[36m[deploy]\x1b[0m ${msg}`);
}

if (!process.env.NETLIFY_AUTH_TOKEN) {
  fail('NETLIFY_AUTH_TOKEN is not set. Add it in Replit Secrets.');
}
if (!process.env.NETLIFY_SITE_ID) {
  fail('NETLIFY_SITE_ID is not set. Add it in Replit Secrets.');
}
if (!fs.existsSync(SITE_DIR)) {
  fail(`Site directory not found: ${SITE_DIR}`);
}

const NETLIFY_BIN = path.join(__dirname, '..', 'node_modules', '.bin', 'netlify');

const args = [
  'deploy',
  '--dir', SITE_DIR,
  '--site', process.env.NETLIFY_SITE_ID,
  '--auth', process.env.NETLIFY_AUTH_TOKEN,
];

if (fs.existsSync(FUNCTIONS_DIR)) {
  args.push('--functions', FUNCTIONS_DIR);
}

if (isDraft) {
  info('Publishing a DRAFT preview deploy (not production).');
} else {
  args.push('--prod');
  info('Publishing a PRODUCTION deploy to your Netlify site.');
}

info(`Site dir:      ${SITE_DIR}`);
if (fs.existsSync(FUNCTIONS_DIR)) {
  info(`Functions dir: ${FUNCTIONS_DIR}`);
}

const usingLocalBin = fs.existsSync(NETLIFY_BIN);
const cmd = usingLocalBin ? NETLIFY_BIN : 'npx';
const cmdArgs = usingLocalBin ? args : ['--yes', 'netlify-cli@latest', ...args];

const result = spawnSync(cmd, cmdArgs, {
  stdio: 'inherit',
  env: process.env,
});

if (result.status !== 0) {
  fail(`netlify-cli exited with code ${result.status}`);
}

info('Deploy complete.');
