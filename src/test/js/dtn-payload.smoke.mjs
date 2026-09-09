import assert from 'node:assert/strict';
import {payloadUrl, displayedJson, createPayloadViewer} from '../../main/resources/static/assets/dtn-payload.js';

const original = '{\r\n  "note": "한글 <script>alert(1)</script>", "value": 1\r\n}\r\n';
assert.equal(displayedJson(original, false), original);
assert.deepEqual(JSON.parse(displayedJson(original, true)), JSON.parse(original));
assert.equal(payloadUrl('a/b', 'sent', true), '/lnis/api/v1/dtn/tests/a%2Fb/payload/sent?download=true');
assert.throws(() => payloadUrl('id', 'unknown'));

// 브라우저와 같은 DOM 조작 경계를 제공한다. 본문은 textarea.value에만 들어가야 한다.
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.value = ''; this.hidden = false; }
  append(...elements) { this.children.push(...elements); }
  setAttribute(name, value) { this[name] = value; }
  removeAttribute(name) { delete this[name]; }
}
globalThis.document = {createElement: tag => new Element(tag), createTextNode: text => ({textContent: text})};
const container = new Element('section');
const viewer = createPayloadViewer(container);
const [title, description, controls, status, panel] = container.children;
const [sent, received] = controls.children;
const [caption, toolbar, text] = panel.children;
const [prettyLabel, download, close] = toolbar.children;
const pretty = prettyLabel.children[0];
assert.equal(sent.disabled, true);
assert.equal(received.disabled, true);
viewer.setJob({testId: 'first', sentPayloadAvailable: true, receivedPayloadAvailable: false});
assert.equal(sent.disabled, false);
assert.equal(received.disabled, true);
globalThis.fetch = async () => ({ok: true, text: async () => original, headers: {get: () => 'original'}});
await sent.onclick();
assert.equal(text.value, original);
assert.equal(panel.hidden, false);
assert.equal(download.href, payloadUrl('first', 'sent', true));
pretty.checked = true;
pretty.onchange();
assert.equal(text.value, displayedJson(original, true));
assert.equal(download.href, payloadUrl('first', 'sent', true));
pretty.checked = false;
pretty.onchange();
assert.equal(text.value, original);

// 이전 시험 조회가 늦게 도착해도 새 시험의 내용으로 표시하지 않는다.
let finish;
globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
const loading = sent.onclick();
viewer.setJob({testId: 'second', sentPayloadAvailable: true});
finish({ok: true, text: async () => original, headers: {get: () => 'original'}});
await loading;
assert.equal(panel.hidden, true);
assert.equal(text.value, '');
assert.equal(download.href, undefined);
close.onclick();
assert.equal(panel.hidden, true);
console.log('PASS: DTN JSON original/pretty/download, availability and stale-response guards');
