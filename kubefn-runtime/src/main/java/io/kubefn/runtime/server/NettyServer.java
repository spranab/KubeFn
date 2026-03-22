package io.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.runtime.config.RuntimeConfig;
import io.kubefn.runtime.lifecycle.DrainManager;
import io.kubefn.runtime.resilience.FallbackRegistry;
import io.kubefn.runtime.resilience.FunctionCircuitBreaker;
import io.kubefn.runtime.routing.FunctionRouter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production-grade Netty server with all hardening components wired.
 */
public class NettyServer {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final RuntimeConfig config;
    private final FunctionRouter router;
    private final ObjectMapper objectMapper;
    private final ExecutorService functionExecutor;
    private final FunctionCircuitBreaker circuitBreaker;
    private final FallbackRegistry fallbackRegistry;
    private final DrainManager drainManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(RuntimeConfig config, FunctionRouter router,
                       FunctionCircuitBreaker circuitBreaker,
                       FallbackRegistry fallbackRegistry,
                       DrainManager drainManager) {
        this.config = config;
        this.router = router;
        this.circuitBreaker = circuitBreaker;
        this.fallbackRegistry = fallbackRegistry;
        this.drainManager = drainManager;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
        this.functionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(config.maxRequestBodyBytes()));
                        pipeline.addLast(new RequestDispatcher(
                                router, functionExecutor, objectMapper, config,
                                circuitBreaker, fallbackRegistry, drainManager));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture future = bootstrap.bind(config.port()).sync();
        serverChannel = future.channel();

        log.info("╔════════════════════════════════════════════════════╗");
        log.info("║   KubeFn v0.2 — Live Application Fabric            ║");
        log.info("║   Memory-Continuous Architecture                    ║");
        log.info("╠════════════════════════════════════════════════════╣");
        log.info("║  HTTP:  port {}                                    ║", config.port());
        log.info("║  Admin: port {}                                    ║", config.adminPort());
        log.info("║  Functions: {}  ║", config.functionsDir());
        log.info("║  Concurrency/group: {}                             ║", config.maxConcurrencyPerGroup());
        log.info("║  Request timeout:   {}ms                           ║", config.requestTimeoutMs());
        log.info("║  Virtual threads:   enabled                         ║");
        log.info("║  Circuit breakers:  enabled                         ║");
        log.info("║  Drain manager:     enabled                         ║");
        log.info("║  HeapGuard:         enabled                         ║");
        log.info("║  OpenTelemetry:     enabled                         ║");
        log.info("╚════════════════════════════════════════════════════╝");
    }

    public void stop() {
        log.info("Shutting down KubeFn runtime...");
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        functionExecutor.shutdown();
        log.info("KubeFn runtime stopped.");
    }

    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) serverChannel.closeFuture().sync();
    }

    public FunctionRouter router() { return router; }
    public ObjectMapper objectMapper() { return objectMapper; }
    public FunctionCircuitBreaker circuitBreaker() { return circuitBreaker; }
}
