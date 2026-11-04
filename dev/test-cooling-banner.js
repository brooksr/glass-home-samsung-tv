'use strict';
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

class Element {
  constructor() { this.children = []; this.classList = { toggle() {} }; }
  get firstChild() { return this.children[0]; }
  appendChild(child) { this.children.push(child); }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); }
}

function banners(state) {
  const roots = { banners: new Element(), upnext: new Element() };
  const GH = { config: { alerts: { entities: { cooling: 'binary_sensor.cooler_air_outside' } } },
    ha: { getState(id, callback) { callback(state); } } };
  const context = { window: { GH }, document: { getElementById: id => roots[id], createElement: () => new Element(), addEventListener() {} },
    Date, Intl, setInterval() {}, clearInterval() {} };
  vm.runInNewContext(fs.readFileSync('app/js/alerts.js', 'utf8'), context);
  return roots.banners.children;
}

const valid = { state: 'on', attributes: { indoor_temperature: 76, outdoor_temperature: 69, checked_at: new Date().toISOString() } };
let result = banners(valid);
assert.equal(result.length, 1);
assert.equal(result[0].className, 'banner info');
assert.equal(result[0].children[1].textContent, 'Cooler outside');
assert.equal(result[0].children[2].textContent, '76°F inside · 69°F outside');
assert.equal(banners({ ...valid, state: 'off' }).length, 0);
assert.equal(banners({ ...valid, state: 'unavailable' }).length, 0);
assert.equal(banners({ ...valid, attributes: { ...valid.attributes, checked_at: new Date(Date.now() - 6 * 60 * 1000).toISOString() } }).length, 0);
assert.equal(banners({ ...valid, attributes: { ...valid.attributes, indoor_temperature: null } }).length, 0);
assert.equal(banners(null).length, 0);
console.log('Cooling banner tests passed.');
