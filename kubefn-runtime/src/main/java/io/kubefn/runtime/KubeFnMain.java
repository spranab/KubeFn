package io.kubefn.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.runtime.classloader.FunctionLoader;
import io.kubefn.runtime.config.RuntimeConfig;
import io.kubefn.runtime.heap.HeapExchangeImpl;
import io.kubefn.runtime.resilience.FunctionCircuitBreaker;
import io.kubefn.runtime.routing.FunctionRouter;
import io.kubefn.runtime.server.AdminHandler;
import io.kubefn.runtime.server.NettyServer;
import io.kubefn.runtime.watcher.FunctionWatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * KubeFn Runtime v0.2 — The Living Application Fabric.
 *
 * <p>Boots the organism with:
 * <ul>
 *   <li>HeapExchange (shared object graph fabric)</li>
 *   <li>OpenTelemetry tracing per function invocation</li>
 *   <li>Circuit breakers for failure isolation</li>
 *   <li>Revision-pinned execution contexts</li>
 *   <li>Heap mutation audit log</li>
 *   <li>File watcher for hot-reload</li>
 * </ul>
 */
public class KubeFnMain {

    private static final Logger log = LoggerFactory.getLogger(KubeFnMain.class);

    public static void main(String[] args) throws Exception {
        log.info("Booting KubeFn organism v0.2...");

        // Load config
        RuntimeConfig config = RuntimeConfig.fromEnv();

        // Create core components
        HeapExchangeImpl heapExchange = new HeapExchangeImpl();
        FunctionRouter router = new FunctionRouter();
        FunctionCircuitBreaker circuitBreaker = new FunctionCircuitBreaker();
        FunctionLoader loader = new FunctionLoader(router, heapExchange);

        // Start main HTTP server with all v0.2 features
        NettyServer server = new NettyServer(config, router, circuitBreaker);
        server.start();

        // Start admin server
        startAdminServer(config, router, server.objectMapper(), heapExchange, circuitBreaker);

        // Load existing function groups
        if (Files.exists(config.functionsDir())) {
            loader.loadAll(config.functionsDir());
        } else {
            Files.createDirectories(config.functionsDir());
            log.info("Created functions directory: {}", config.functionsDir());
        }

        // Start file watcher for hot-reload
        FunctionWatcher watcher = new FunctionWatcher(config.functionsDir(), loader);
        Thread.startVirtualThread(watcher);

        log.info("KubeFn organism is ALIVE. {} routes registered.", router.routeCount());
        log.info("Drop function JARs into {} to deploy.", config.functionsDir());

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Draining organism...");
            watcher.stop();
            server.stop();
        }, "kubefn-shutdown"));

        server.awaitTermination();
    }

    private static void startAdminServer(RuntimeConfig config, FunctionRouter router,
                                         ObjectMapper objectMapper, HeapExchangeImpl heapExchange,
                                         FunctionCircuitBreaker circuitBreaker)
            throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new AdminHandler(
                                router, objectMapper, heapExchange, circuitBreaker));
                    }
                });

        bootstrap.bind(config.adminPort()).sync();
        log.info("Admin server listening on port {}", config.adminPort());
    }
}
