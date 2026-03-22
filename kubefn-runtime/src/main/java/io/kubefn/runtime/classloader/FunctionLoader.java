package io.kubefn.runtime.classloader;

import io.kubefn.api.*;
import io.kubefn.runtime.context.FunctionGroupContext;
import io.kubefn.runtime.heap.HeapExchangeImpl;
import io.kubefn.runtime.lifecycle.DrainManager;
import io.kubefn.runtime.routing.FunctionEntry;
import io.kubefn.runtime.routing.FunctionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads function groups from thin JARs into the living organism.
 *
 * <p>For each group directory:
 * <ol>
 *   <li>Create a FunctionGroupClassLoader with the JAR(s)</li>
 *   <li>Scan for classes implementing KubeFnHandler</li>
 *   <li>Read @FnRoute and @FnGroup annotations</li>
 *   <li>Instantiate, inject context, register routes</li>
 * </ol>
 *
 * <p>Born-warm: new function revisions enter an already-hot JVM.
 * Shared libraries (Jackson, Netty, etc.) are already JIT-compiled.
 * Only the function's own code needs warmup.
 */
public class FunctionLoader {

    private static final Logger log = LoggerFactory.getLogger(FunctionLoader.class);

    private final FunctionRouter router;
    private final HeapExchangeImpl heapExchange;
    private final DrainManager drainManager;
    private final Map<String, FunctionGroupClassLoader> activeClassLoaders = new HashMap<>();
    private final Map<String, FunctionGroupContext> activeContexts = new HashMap<>();

    public FunctionLoader(FunctionRouter router, HeapExchangeImpl heapExchange,
                          DrainManager drainManager) {
        this.router = router;
        this.heapExchange = heapExchange;
        this.drainManager = drainManager;
    }

    /**
     * Load all function groups from the functions directory.
     * Each subdirectory is a group containing JAR(s) and/or .class files.
     */
    public void loadAll(Path functionsDir) throws IOException {
        if (!Files.exists(functionsDir)) {
            log.warn("Functions directory does not exist: {}", functionsDir);
            Files.createDirectories(functionsDir);
            return;
        }

        try (DirectoryStream<Path> groups = Files.newDirectoryStream(functionsDir)) {
            for (Path groupDir : groups) {
                if (Files.isDirectory(groupDir)) {
                    String groupName = groupDir.getFileName().toString();
                    loadGroup(groupName, groupDir);
                }
            }
        }

        log.info("Loaded {} groups with {} total routes",
                activeClassLoaders.size(), router.routeCount());
    }

    /**
     * Load or reload a single function group.
     */
    public synchronized void loadGroup(String groupName, Path groupDir) {
        log.info("Loading function group: {} from {}", groupName, groupDir);

        // Unload existing group if reloading
        unloadGroup(groupName);

        try {
            // Collect all JARs and class directories
            List<URL> urls = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir)) {
                for (Path file : files) {
                    if (file.toString().endsWith(".jar")) {
                        urls.add(file.toUri().toURL());
                    } else if (Files.isDirectory(file)) {
                        // Support loose class files in subdirectories
                        urls.add(file.toUri().toURL());
                    }
                }
            }

            // Also add the group directory itself (for loose .class files)
            urls.add(groupDir.toUri().toURL());

            if (urls.isEmpty()) {
                log.warn("No JARs or classes found in group directory: {}", groupDir);
                return;
            }

            // Generate revision ID from content hash
            String revisionId = generateRevisionId(groupDir);

            // Create child-first classloader — BORN WARM
            // The parent classloader already has JIT-compiled platform code
            FunctionGroupClassLoader classLoader = new FunctionGroupClassLoader(
                    groupName,
                    urls.toArray(new URL[0]),
                    getClass().getClassLoader()  // Runtime's classloader as parent
            );

            // Create group context
            FunctionGroupContext context = new FunctionGroupContext(
                    groupName, revisionId, heapExchange, Map.of());

            // Scan and load functions
            List<Class<?>> handlerClasses = scanForHandlers(classLoader, urls);

            for (Class<?> handlerClass : handlerClasses) {
                loadFunction(handlerClass, groupName, revisionId, context, classLoader);
            }

            // Store active references
            activeClassLoaders.put(groupName, classLoader);
            activeContexts.put(groupName, context);

            log.info("Group '{}' loaded: rev={}, functions={}",
                    groupName, revisionId, context.functionRegistry().size());

        } catch (Exception e) {
            log.error("Failed to load group: {}", groupName, e);
        }
    }

    /**
     * Unload a function group — discard classloader, unregister routes.
     */
    public synchronized void unloadGroup(String groupName) {
        FunctionGroupClassLoader classLoader = activeClassLoaders.remove(groupName);
        FunctionGroupContext context = activeContexts.remove(groupName);

        if (classLoader != null) {
            // Graceful drain: wait for in-flight requests to complete
            log.info("Draining group '{}' before unload...", groupName);
            boolean drained = drainManager.drainAndWait(groupName, 10_000); // 10s timeout
            if (!drained) {
                log.warn("Drain timeout for group '{}'. Forcing unload with {} in-flight.",
                        groupName, drainManager.inFlightCount(groupName));
            }

            // Call lifecycle hooks
            if (context != null) {
                context.functionRegistry().values().forEach(handler -> {
                    if (handler instanceof FnLifecycle lifecycle) {
                        try {
                            lifecycle.onDrain();
                            lifecycle.onClose();
                        } catch (Exception e) {
                            log.warn("Lifecycle hook error for group {}", groupName, e);
                        }
                    }
                });
            }

            // Unregister all routes
            router.unregisterGroup(groupName);

            // Close classloader to release resources
            try {
                classLoader.close();
            } catch (IOException e) {
                log.warn("Error closing classloader for group: {}", groupName, e);
            }

            log.info("Unloaded group: {}", groupName);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFunction(Class<?> handlerClass, String groupName, String revisionId,
                              FunctionGroupContext context, ClassLoader classLoader) {
        try {
            // Validate it implements KubeFnHandler
            if (!KubeFnHandler.class.isAssignableFrom(handlerClass)) {
                return;
            }

            // Read annotations
            FnRoute routeAnn = handlerClass.getAnnotation(FnRoute.class);
            if (routeAnn == null) {
                log.debug("Skipping handler without @FnRoute: {}", handlerClass.getName());
                return;
            }

            // Instantiate
            KubeFnHandler handler = (KubeFnHandler) handlerClass.getDeclaredConstructor().newInstance();

            // Inject context
            if (handler instanceof FnContextAware aware) {
                aware.setContext(context);
            }

            // Register in group context (for pipeline and getFunction)
            context.registerFunction((Class<? extends KubeFnHandler>) handlerClass, handler);

            // Call init lifecycle hook
            if (handler instanceof FnLifecycle lifecycle) {
                lifecycle.onInit();
            }

            // Register route
            String functionName = handlerClass.getSimpleName();
            FunctionEntry entry = new FunctionEntry(
                    groupName, functionName, handlerClass.getName(), revisionId, handler);
            router.register(routeAnn.methods(), routeAnn.path(), entry);

        } catch (Exception e) {
            log.error("Failed to load function: {}", handlerClass.getName(), e);
        }
    }

    /**
     * Scan URLs for classes that implement KubeFnHandler.
     */
    private List<Class<?>> scanForHandlers(ClassLoader classLoader, List<URL> urls) {
        List<Class<?>> handlers = new ArrayList<>();

        for (URL url : urls) {
            try {
                Path path = Paths.get(url.toURI());
                if (path.toString().endsWith(".jar")) {
                    scanJar(path, classLoader, handlers);
                } else if (Files.isDirectory(path)) {
                    scanDirectory(path, path, classLoader, handlers);
                }
            } catch (Exception e) {
                log.debug("Error scanning URL: {}", url, e);
            }
        }

        return handlers;
    }

    private void scanJar(Path jarPath, ClassLoader classLoader, List<Class<?>> handlers) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().contains("module-info")) {
                    String className = entry.getName()
                            .replace('/', '.')
                            .replace(".class", "");
                    tryLoadHandler(className, classLoader, handlers);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning JAR: {}", jarPath, e);
        }
    }

    private void scanDirectory(Path root, Path dir, ClassLoader classLoader, List<Class<?>> handlers) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (Files.isDirectory(file)) {
                    scanDirectory(root, file, classLoader, handlers);
                } else if (file.toString().endsWith(".class") && !file.toString().contains("module-info")) {
                    String relative = root.relativize(file).toString();
                    String className = relative
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replace(".class", "");
                    tryLoadHandler(className, classLoader, handlers);
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning directory: {}", dir, e);
        }
    }

    private void tryLoadHandler(String className, ClassLoader classLoader, List<Class<?>> handlers) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            if (KubeFnHandler.class.isAssignableFrom(clazz)
                    && !clazz.isInterface()
                    && clazz.getAnnotation(FnRoute.class) != null) {
                handlers.add(clazz);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Expected for non-handler classes
        }
    }

    private String generateRevisionId(Path groupDir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DirectoryStream<Path> files = Files.newDirectoryStream(groupDir)) {
                for (Path file : files) {
                    if (Files.isRegularFile(file)) {
                        digest.update(Files.readAllBytes(file));
                    }
                }
            }
            byte[] hash = digest.digest();
            return "rev-" + bytesToHex(hash).substring(0, 12);
        } catch (Exception e) {
            return "rev-" + System.currentTimeMillis();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public Map<String, FunctionGroupClassLoader> activeClassLoaders() {
        return Collections.unmodifiableMap(activeClassLoaders);
    }
}
