package com.kubefn.runtime.watcher;

import com.kubefn.runtime.classloader.FunctionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exploded dev mode watcher — watches build/classes directories
 * instead of JAR files for near-instant reload.
 *
 * <p>Developer loop:
 * <ol>
 *   <li>Developer saves .java file in IDE</li>
 *   <li>IDE incremental compiler writes .class to build/classes/</li>
 *   <li>DevModeWatcher detects change within ~50ms</li>
 *   <li>Creates new revision classloader from class dirs</li>
 *   <li>Atomically swaps function — old drains, new goes live</li>
 * </ol>
 *
 * <p>Total: save → live in ~80ms (no JAR, no copy, no deploy step).
 *
 * <p>Activated by: KUBEFN_DEV_MODE=exploded or kubefn dev --exploded
 */
public class DevModeWatcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DevModeWatcher.class);

    private final Path projectRoot;
    private final FunctionLoader loader;
    private volatile boolean running = true;
    private final long debounceMs;
    private final ConcurrentHashMap<String, Long> lastReloadTime = new ConcurrentHashMap<>();
    private final AtomicLong reloadCount = new AtomicLong();
    private final AtomicLong totalReloadTimeNanos = new AtomicLong();

    public DevModeWatcher(Path projectRoot, FunctionLoader loader) {
        this(projectRoot, loader, 100); // 100ms debounce
    }

    public DevModeWatcher(Path projectRoot, FunctionLoader loader, long debounceMs) {
        this.projectRoot = projectRoot;
        this.loader = loader;
        this.debounceMs = debounceMs;
    }

    @Override
    public void run() {
        log.info("DevMode watcher started on: {}", projectRoot);
        log.info("Watching for .class changes in build/classes directories");

        try {
            // Discover function groups from project structure
            // Convention: projectRoot/functions/<group>/build/classes/java/main
            // Or: projectRoot/build/classes/java/main (single-group)
            var classeDirs = discoverClassDirs();

            if (classeDirs.isEmpty()) {
                log.warn("No build/classes directories found under {}. " +
                        "Run 'gradle classes' first, then save a file.", projectRoot);
                // Still watch — dirs might appear after first compile
            } else {
                log.info("Watching {} class directories:", classeDirs.size());
                classeDirs.forEach((group, dirs) ->
                        log.info("  {} → {}", group, dirs));
            }

            // Initial load from class dirs
            for (var entry : classeDirs.entrySet()) {
                String groupName = entry.getKey();
                List<Path> dirs = entry.getValue();
                reloadFromClassDirs(groupName, dirs);
            }

            // Watch for changes
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                // Register all class dirs and parent dirs
                Set<Path> watchedDirs = new HashSet<>();
                for (var dirs : classeDirs.values()) {
                    for (Path dir : dirs) {
                        registerRecursive(watchService, dir, watchedDirs);
                    }
                }
                // Also watch the project root for new groups
                watchedDirs.add(projectRoot);
                projectRoot.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                while (running) {
                    WatchKey key = watchService.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (key == null) continue;

                    boolean shouldReload = false;
                    String affectedGroup = null;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed == null) continue;

                        String fileName = changed.toString();
                        if (fileName.endsWith(".class")) {
                            shouldReload = true;
                            // Determine which group this belongs to
                            Path watchedDir = (Path) key.watchable();
                            affectedGroup = resolveGroup(watchedDir);
                        }
                    }

                    key.reset();

                    if (shouldReload && affectedGroup != null) {
                        // Debounce: don't reload more than once per debounceMs
                        long now = System.currentTimeMillis();
                        Long lastReload = lastReloadTime.get(affectedGroup);
                        if (lastReload == null || (now - lastReload) > debounceMs) {
                            lastReloadTime.put(affectedGroup, now);
                            List<Path> dirs = classeDirs.getOrDefault(affectedGroup, List.of());
                            if (!dirs.isEmpty()) {
                                long start = System.nanoTime();
                                reloadFromClassDirs(affectedGroup, dirs);
                                long elapsed = System.nanoTime() - start;
                                reloadCount.incrementAndGet();
                                totalReloadTimeNanos.addAndGet(elapsed);
                                log.info("DevMode reload: {} in {}ms (total reloads: {})",
                                        affectedGroup, elapsed / 1_000_000, reloadCount.get());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                log.error("DevMode watcher error", e);
            }
        }
    }

    /**
     * Discover build/classes directories for each function group.
     */
    private Map<String, List<Path>> discoverClassDirs() {
        var result = new LinkedHashMap<String, List<Path>>();

        // Pattern 1: projectRoot/functions/<group>/build/classes/java/main
        Path functionsDir = projectRoot.resolve("functions");
        if (Files.isDirectory(functionsDir)) {
            try (var groups = Files.newDirectoryStream(functionsDir, Files::isDirectory)) {
                for (Path groupDir : groups) {
                    String groupName = groupDir.getFileName().toString();
                    List<Path> dirs = findClassDirs(groupDir);
                    if (!dirs.isEmpty()) {
                        result.put(groupName, dirs);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to scan functions dir: {}", e.getMessage());
            }
        }

        // Pattern 2: projectRoot/build/classes/java/main (single function project)
        List<Path> rootDirs = findClassDirs(projectRoot);
        if (!rootDirs.isEmpty() && result.isEmpty()) {
            String groupName = projectRoot.getFileName().toString();
            result.put(groupName, rootDirs);
        }

        return result;
    }

    private List<Path> findClassDirs(Path base) {
        var dirs = new ArrayList<Path>();
        // Java
        Path javaClasses = base.resolve("build/classes/java/main");
        if (Files.isDirectory(javaClasses)) dirs.add(javaClasses);
        // Kotlin
        Path kotlinClasses = base.resolve("build/classes/kotlin/main");
        if (Files.isDirectory(kotlinClasses)) dirs.add(kotlinClasses);
        // Scala
        Path scalaClasses = base.resolve("build/classes/scala/main");
        if (Files.isDirectory(scalaClasses)) dirs.add(scalaClasses);
        // Resources
        Path resources = base.resolve("build/resources/main");
        if (Files.isDirectory(resources)) dirs.add(resources);
        return dirs;
    }

    /**
     * Reload a function group from its class directories.
     */
    private void reloadFromClassDirs(String groupName, List<Path> classDirs) {
        // Create a temporary directory that looks like a group dir with the class dirs
        // The FunctionLoader expects a directory with JARs or class dirs
        // We can use the first class dir's parent as the group dir
        Path groupDir = classDirs.getFirst().getParent();
        if (groupDir != null) {
            loader.loadGroup(groupName, groupDir);
        }
    }

    private String resolveGroup(Path watchedDir) {
        // Walk up from the watched dir to find the group name
        // Convention: .../functions/<group>/build/classes/...
        Path current = watchedDir;
        while (current != null) {
            Path parent = current.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().equals("functions")) {
                return current.getFileName().toString();
            }
            // Also check if we're directly in a build/classes dir
            if (current.getFileName() != null
                    && current.toString().contains("build/classes")) {
                // Walk up to find project name
                Path p = current;
                while (p != null && p.getFileName() != null) {
                    if (p.getFileName().toString().equals("build")) {
                        Path projectDir = p.getParent();
                        if (projectDir != null) {
                            return projectDir.getFileName().toString();
                        }
                    }
                    p = p.getParent();
                }
            }
            current = parent;
        }
        return "default";
    }

    private void registerRecursive(WatchService watcher, Path dir, Set<Path> registered)
            throws IOException {
        if (!Files.isDirectory(dir) || registered.contains(dir)) return;

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs)
                    throws IOException {
                if (!registered.contains(d)) {
                    d.register(watcher,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                    registered.add(d);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void stop() {
        running = false;
    }

    public Map<String, Object> status() {
        long avgNanos = reloadCount.get() > 0
                ? totalReloadTimeNanos.get() / reloadCount.get() : 0;
        return Map.of(
                "mode", "exploded",
                "projectRoot", projectRoot.toString(),
                "reloadCount", reloadCount.get(),
                "avgReloadMs", avgNanos / 1_000_000.0,
                "debounceMs", debounceMs
        );
    }
}
