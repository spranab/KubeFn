"""
KubeFn Python Runtime — Memory-Continuous Architecture for Python.

Same concept as the JVM runtime: multiple independently deployable
functions share a single Python interpreter, exchanging objects
in shared memory with zero serialization.

    from kubefn import function, heap_exchange, KubeFnApp

    @function("/predict", methods=["POST"], group="ml-pipeline")
    def predict(request, ctx):
        features = ctx.heap.get("features")  # Zero-copy from shared heap
        prediction = model.predict(features)
        ctx.heap.publish("prediction", prediction)
        return {"score": prediction}
"""

__version__ = "0.4.0"

from .decorators import function
from .scheduler import schedule
from .heap_exchange import HeapExchange
from .context import FnContext, FnRequest
