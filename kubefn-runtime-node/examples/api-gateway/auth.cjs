/**
 * JWT Authentication function — validates tokens and publishes
 * auth context to HeapExchange for downstream functions.
 */

function authenticate(request, ctx) {
  const token = request.queryParams.token || 'demo-token';
  const userId = request.queryParams.userId || 'user-001';

  // Simulate JWT validation
  const authContext = {
    userId,
    authenticated: true,
    roles: ['user', 'premium'],
    permissions: ['read', 'write', 'admin'],
    tokenExpiry: Date.now() + 3600000,
    sessionId: `sess-${Date.now().toString(36)}`,
  };

  // Publish to HeapExchange — ALL downstream functions read this zero-copy
  ctx.heap.publish('auth:context', authContext, 'AuthContext');

  return { authenticated: true, userId, roles: authContext.roles };
}
authenticate._kubefn = { path: '/gw/auth', methods: ['GET', 'POST'] };

/**
 * Rate limiter — token bucket algorithm using shared heap.
 */
function rateLimit(request, ctx) {
  const clientId = request.queryParams.clientId || 'default';
  const key = `ratelimit:${clientId}`;

  let bucket = ctx.heap.get(key);
  if (!bucket) {
    bucket = { tokens: 100, lastRefill: Date.now(), maxTokens: 100, refillRate: 10 };
  }

  // Refill tokens
  const elapsed = (Date.now() - bucket.lastRefill) / 1000;
  bucket.tokens = Math.min(bucket.maxTokens, bucket.tokens + elapsed * bucket.refillRate);
  bucket.lastRefill = Date.now();

  // Consume token
  if (bucket.tokens < 1) {
    return { allowed: false, remaining: 0, retryAfterMs: 100 };
  }

  bucket.tokens--;
  ctx.heap.publish(key, bucket, 'RateLimitBucket');

  return { allowed: true, remaining: Math.floor(bucket.tokens) };
}
rateLimit._kubefn = { path: '/gw/ratelimit', methods: ['GET', 'POST'] };

/**
 * API Gateway orchestrator — auth + rate limit + route.
 */
function gateway(request, ctx) {
  const start = process.hrtime.bigint();

  // Step 1: Rate limit
  const t1 = process.hrtime.bigint();
  const rateLimitResult = rateLimit(request, ctx);
  const d1 = Number(process.hrtime.bigint() - t1) / 1_000_000;

  if (!rateLimitResult.allowed) {
    return { error: 'Rate limited', retryAfterMs: rateLimitResult.retryAfterMs };
  }

  // Step 2: Authenticate
  const t2 = process.hrtime.bigint();
  const authResult = authenticate(request, ctx);
  const d2 = Number(process.hrtime.bigint() - t2) / 1_000_000;

  // Step 3: Read auth context from heap (zero-copy)
  const authContext = ctx.heap.get('auth:context');

  const totalMs = Number(process.hrtime.bigint() - start) / 1_000_000;

  return {
    gateway: 'KubeFn Node.js API Gateway',
    auth: authResult,
    rateLimit: rateLimitResult,
    authContext: authContext ? { userId: authContext.userId, roles: authContext.roles } : null,
    heapObjects: ctx.heap.size(),
    _meta: {
      pipelineSteps: 3,
      totalTimeMs: totalMs.toFixed(3),
      stages: {
        rateLimit: `${d1.toFixed(3)}ms`,
        authenticate: `${d2.toFixed(3)}ms`,
      },
      zeroCopy: true,
      runtime: 'node',
      note: '3 JS functions sharing V8 isolate. Zero serialization.',
    },
  };
}
gateway._kubefn = { path: '/gw/proxy', methods: ['GET', 'POST'] };

module.exports = { authenticate, rateLimit, gateway };
