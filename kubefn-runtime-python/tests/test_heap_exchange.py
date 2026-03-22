"""Tests for the Python HeapExchange — zero-copy shared object store."""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from kubefn.heap_exchange import HeapExchange


def test_publish_and_get_returns_same_object():
    heap = HeapExchange()
    data = {"key": "value", "nested": [1, 2, 3]}
    heap.publish("test", data)

    result = heap.get("test")
    assert result is data  # ZERO COPY: same object reference
    assert id(result) == id(data)


def test_get_returns_none_for_missing():
    heap = HeapExchange()
    assert heap.get("nonexistent") is None


def test_remove_deletes_object():
    heap = HeapExchange()
    heap.publish("k1", "value")
    assert heap.contains("k1")

    heap.remove("k1")
    assert not heap.contains("k1")
    assert heap.get("k1") is None


def test_keys_returns_all_published():
    heap = HeapExchange()
    heap.publish("a", 1)
    heap.publish("b", 2)
    heap.publish("c", 3)

    keys = heap.keys()
    assert set(keys) == {"a", "b", "c"}


def test_metrics_track_operations():
    heap = HeapExchange()
    heap.publish("k1", "v1")
    heap.publish("k2", "v2")
    heap.get("k1")  # hit
    heap.get("k2")  # hit
    heap.get("k3")  # miss

    m = heap.metrics()
    assert m["objectCount"] == 2
    assert m["publishCount"] == 2
    assert m["getCount"] == 3
    assert m["hitCount"] == 2
    assert m["missCount"] == 1


def test_capacity_limit():
    heap = HeapExchange(max_objects=3)
    heap.publish("k1", "v1")
    heap.publish("k2", "v2")
    heap.publish("k3", "v3")

    try:
        heap.publish("k4", "v4")
        assert False, "Should have raised"
    except RuntimeError as e:
        assert "capacity" in str(e).lower()


def test_overwrite_existing_key():
    heap = HeapExchange()
    heap.publish("key", "v1")
    heap.publish("key", "v2")

    assert heap.get("key") == "v2"
    assert heap.size() == 1


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"  PASS: {name}")
            except Exception as e:
                print(f"  FAIL: {name} — {e}")
    print(f"\nAll Python HeapExchange tests complete.")
