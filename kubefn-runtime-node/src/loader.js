/**
 * Function Loader — dynamically loads ES module or CommonJS functions from
 * a directory, discovers exported handlers, registers routes, and wires
 * scheduled functions into the SchedulerEngine.
 *
 * Directory layout:
 *   functionsDir/
 *     group-a/
 *       pricing.js      ← exports handler functions with _kubefn metadata
 *       inventory.mjs   ← ES module variant
 *     group-b/
 *       ...
 *
 * Function metadata (set on the export):
 *   myHandler._kubefn = { path: '/my/route', methods: ['GET', 'POST'] }
 *
 * Schedule metadata (named export):
 *   export const schedule = { cron: '0 * * * *' }
 */

import { readdir, stat, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

export class FunctionLoader {
  /**
   * @param {string} functionsDir
   * @param {import('./heap-exchange.js').HeapExchange} heap
   * @param {object} [opts]
   * @param {import('./scheduler.js').SchedulerEngine} [opts.scheduler]
   */
  constructor(functionsDir, heap, { scheduler } = {}) {
    this.functionsDir = resolve(functionsDir);
    this.heap = heap;
    this.scheduler = scheduler || null;

    /** @type {Record<string, FnInfo[]>} */
    this.loadedGroups = {};

    /** @type {Map<string, FnInfo>} path → fn info */
    this.routes = new Map();
  }

  /**
   * Load all function groups from the functions directory.
   */
  async loadAll() {
    if (!existsSync(this.functionsDir)) {
      await mkdir(this.functionsDir, { recursive: true });
      console.log(`Created functions directory: ${this.functionsDir}`);
      return;
    }

    const entries = await readdir(this.functionsDir, { withFileTypes: true });
    const groups = entries.filter(d => d.isDirectory() && !d.name.startsWith('.'));

    for (const group of groups) {
      await this.loadGroup(group.name);
    }

    const totalRoutes = Object.values(this.loadedGroups)
      .reduce((sum, fns) => sum + fns.length, 0);
    console.log(`Loaded ${Object.keys(this.loadedGroups).length} groups with ${totalRoutes} functions`);
  }

  /**
   * Load (or reload) a single group.
   * @param {string} groupName
   * @returns {FnInfo[]}
   */
  async loadGroup(groupName) {
    const groupDir = join(this.functionsDir, groupName);
    console.log(`Loading function group: ${groupName} from ${groupDir}`);

    // Unload existing
    this.unloadGroup(groupName);

    const functions = [];
    let files;
    try {
      files = await readdir(groupDir);
    } catch {
      console.error(`  Cannot read group directory: ${groupDir}`);
      return functions;
    }

    const jsFiles = files.filter(
      f => (f.endsWith('.js') || f.endsWith('.mjs') || f.endsWith('.cjs')) && !f.startsWith('_')
    );

    for (const jsFile of jsFiles) {
      const filePath = join(groupDir, jsFile);

      try {
        const mod = await this._loadModule(filePath);

        // Module-level schedule metadata
        const moduleSchedule = mod.schedule || null;

        for (const [name, handler] of Object.entries(mod)) {
          if (typeof handler !== 'function') continue;
          if (!handler._kubefn) continue;

          const meta = handler._kubefn;
          const fnInfo = {
            name,
            path: meta.path,
            methods: meta.methods || ['GET', 'POST'],
            group: groupName,
            handler,
            file: jsFile,
            loadedAt: Date.now(),
          };

          functions.push(fnInfo);
          this.routes.set(meta.path, fnInfo);

          for (const method of fnInfo.methods) {
            console.log(`  Registered route: ${method} ${meta.path} -> ${groupName}.${name}`);
          }

          // Register with scheduler if cron metadata exists
          const fnSchedule = handler._kubefn.schedule || moduleSchedule;
          if (fnSchedule && fnSchedule.cron && this.scheduler) {
            const qualifiedName = `${groupName}.${name}`;
            this.scheduler.register(
              qualifiedName,
              fnSchedule.cron,
              () => handler({}, { heap: this.heap, groupName, functionName: name }),
              { group: groupName, function: name }
            );
            console.log(`  Scheduled: ${qualifiedName} @ ${fnSchedule.cron}`);
          }
        }
      } catch (e) {
        console.error(`  Failed to load ${jsFile}: ${e.message}`);
      }
    }

    this.loadedGroups[groupName] = functions;
    return functions;
  }

  /**
   * Unload a group, removing its routes.
   * @param {string} groupName
   */
  unloadGroup(groupName) {
    if (this.loadedGroups[groupName]) {
      for (const fn of this.loadedGroups[groupName]) {
        this.routes.delete(fn.path);
      }
      delete this.loadedGroups[groupName];
    }
  }

  /**
   * Resolve a request to a function.
   * @param {string} method
   * @param {string} requestPath
   * @returns {{ fn: FnInfo, subPath: string } | null}
   */
  resolve(method, requestPath) {
    // Exact match first
    const exact = this.routes.get(requestPath);
    if (exact && exact.methods.includes(method.toUpperCase())) {
      return { fn: exact, subPath: '' };
    }

    // Longest prefix match
    let bestMatch = null;
    let bestLen = 0;
    for (const [routePath, fn] of this.routes) {
      if (requestPath.startsWith(routePath) && routePath.length > bestLen) {
        if (fn.methods.includes(method.toUpperCase())) {
          bestMatch = fn;
          bestLen = routePath.length;
        }
      }
    }

    if (bestMatch) {
      return { fn: bestMatch, subPath: requestPath.slice(bestLen) };
    }
    return null;
  }

  /**
   * List all registered functions.
   * @returns {object[]}
   */
  allFunctions() {
    const fns = [];
    for (const [group, functions] of Object.entries(this.loadedGroups)) {
      for (const fn of functions) {
        for (const method of fn.methods) {
          fns.push({
            method,
            path: fn.path,
            group,
            function: fn.name,
            file: fn.file,
            loadedAt: fn.loadedAt,
            runtime: 'node',
          });
        }
      }
    }
    return fns;
  }

  /**
   * Reload all groups. Returns when complete.
   */
  async reloadAll() {
    // Clear all
    for (const groupName of Object.keys(this.loadedGroups)) {
      this.unloadGroup(groupName);
    }
    await this.loadAll();
  }

  // ── Internal ────────────────────────────────────────────────

  /**
   * Load a module, handling both ESM and CJS.
   * @private
   * @param {string} filePath
   * @returns {Promise<object>}
   */
  async _loadModule(filePath) {
    if (filePath.endsWith('.mjs')) {
      // Always use dynamic import for .mjs
      const url = pathToFileURL(filePath).href + '?t=' + Date.now();
      return await import(url);
    }

    if (filePath.endsWith('.cjs')) {
      // Always use require for .cjs
      delete require.cache[require.resolve(filePath)];
      return require(filePath);
    }

    // For .js files, try CJS first (since existing functions use module.exports)
    try {
      delete require.cache[require.resolve(filePath)];
      return require(filePath);
    } catch {
      // Fall back to ESM dynamic import
      const url = pathToFileURL(filePath).href + '?t=' + Date.now();
      return await import(url);
    }
  }
}
