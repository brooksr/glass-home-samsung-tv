#!/usr/bin/env node
// Writes app/js/env.js from .env: this home's settings (address, location,
// Home Assistant token and entities), both git-ignored so none of it is
// committed. Only the keys below go into the app; the rest of .env (TV MAC,
// SSH target, ...) is for the docs and tools. Run by scripts/build.sh; run it
// yourself after editing .env to see the change in a desktop preview.
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const src = path.join(root, '.env');
const out = path.join(root, 'app', 'js', 'env.js');
const APP_KEYS = {
  LATITUDE: ['latitude', Number], LONGITUDE: ['longitude', Number],
  HA_URL: ['haUrl', String], INSIDE_TEMPERATURE: ['insideTemperature', String],
  INSIDE_HUMIDITY: ['insideHumidity', String], POWER_SHUTOFF: ['powerShutoff', String],
  LAUNCHES: ['launches', String]
};

if (!fs.existsSync(src)) {
  console.error('No .env: copy .env.example to .env and fill it in.');
  process.exit(66);
}
const vars = {};
fs.readFileSync(src, 'utf8').split(/\r?\n/).forEach((line) => {
  const m = /^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
  if (m) vars[m[1]] = m[2].replace(/\s+#.*$/, '').replace(/^(['"])(.*)\1$/, '$2');
});

const env = {};
Object.keys(APP_KEYS).forEach((k) => {
  if (!vars[k]) return;
  const [name, type] = APP_KEYS[k];
  env[name] = type === Number ? Number(vars[k]) : vars[k].replace(/\/+$/, '');
});
fs.writeFileSync(out,
  '// Generated from .env by scripts/make-env-js.js. Git-ignored; do not edit.\n' +
  'window.GH = window.GH || {};\n' +
  'window.GH.env = ' + JSON.stringify(env, null, 2) + ';\n' +
  (vars.HA_TOKEN ? 'window.GH.haToken = ' + JSON.stringify(vars.HA_TOKEN) + ';\n' : ''));
console.log('wrote app/js/env.js');
