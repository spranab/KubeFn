/**
 * Tests for the Node.js HeapExchange — zero-copy shared object store.
 */

const { HeapExchange } = require('../lib/heap-exchange');

let passed = 0;
let failed = 0;

function assert(condition, msg) {
  if (!condition) throw new Error(`Assertion failed: ${msg}`);
}

function test(name, fn) {
  try {
    fn();
    console.log(`  PASS: ${name}`);
    passed++;
  } catch (e) {
    console.log(`  FAIL: ${name} — ${e.message}`);
    failed++;
  }
}

// Tests
test('publish and get returns same object', () => {
  const heap = new HeapExchange();
  const data = { key: 'value', nested: [1, 2, 3] };
  heap.publish('test', data);

  const result = heap.get('test');
  assert(result === data, 'Should be the exact same object reference (zero-copy)');
});

test('get returns undefined for missing key', () => {
  const heap = new HeapExchange();
  assert(heap.get('nonexistent') === undefined, 'Should be undefined');
});

test('remove deletes object', () => {
  const heap = new HeapExchange();
  heap.publish('k1', 'value');
  assert(heap.contains('k1'), 'Should contain k1');

  heap.remove('k1');
  assert(!heap.contains('k1'), 'Should not contain k1 after remove');
});

test('keys returns all published', () => {
  const heap = new HeapExchange();
  heap.publish('a', 1);
  heap.publish('b', 2);
  heap.publish('c', 3);

  const keys = heap.keys();
  assert(keys.length === 3, 'Should have 3 keys');
  assert(keys.includes('a') && keys.includes('b') && keys.includes('c'), 'All keys present');
});

test('metrics track operations', () => {
  const heap = new HeapExchange();
  heap.publish('k1', 'v1');
  heap.publish('k2', 'v2');
  heap.get('k1'); // hit
  heap.get('k2'); // hit
  heap.get('k3'); // miss

  const m = heap.metrics();
  assert(m.objectCount === 2, `objectCount should be 2, got ${m.objectCount}`);
  assert(m.publishCount === 2, `publishCount should be 2, got ${m.publishCount}`);
  assert(m.getCount === 3, `getCount should be 3, got ${m.getCount}`);
  assert(m.hitCount === 2, `hitCount should be 2, got ${m.hitCount}`);
  assert(m.missCount === 1, `missCount should be 1, got ${m.missCount}`);
});

test('capacity limit throws', () => {
  const heap = new HeapExchange(3);
  heap.publish('k1', 'v1');
  heap.publish('k2', 'v2');
  heap.publish('k3', 'v3');

  let threw = false;
  try {
    heap.publish('k4', 'v4');
  } catch (e) {
    threw = true;
    assert(e.message.includes('capacity'), 'Error should mention capacity');
  }
  assert(threw, 'Should have thrown on capacity breach');
});

test('overwrite existing key', () => {
  const heap = new HeapExchange();
  heap.publish('key', 'v1');
  heap.publish('key', 'v2');

  assert(heap.get('key') === 'v2', 'Should return latest value');
  assert(heap.size() === 1, 'Size should be 1');
});

console.log(`\n${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
