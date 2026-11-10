package org.Tyche.src.server;

import org.Tyche.src.core.engine.Scheduler.LoadBalancer;
import org.Tyche.src.core.engine.Scheduler.Scheduler;
import org.Tyche.src.core.engine.Workers.Rayon;
import org.Tyche.src.core.engine.Workers.StockHandler;
import org.Tyche.src.core.engine.GatewayBoundary;
import org.Tyche.src.internal.cache.Repository;
import org.Tyche.src.internal.grpc.GRPC;
import org.Tyche.src.util.BotClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server {

    private final LoadBalancer lb;
    private final Scheduler sch;
    private final BotClock timer;
    private final StockHandler sh;
    private final Repository repo;
    private final Rayon engine;
    public final GatewayBoundary boundary;

    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    public Server() {
        this.repo = new Repository();
        this.lb = new LoadBalancer(repo);
        this.sch = new Scheduler(1);
        this.timer = new BotClock();
        this.sh = new StockHandler(timer);
        this.engine = new Rayon(lb, sh, sch, repo);
        this.boundary = new GatewayBoundary(engine);
    }

    public void StartServer() throws Exception {
        logger.info("Booting server");
        this.engine.BootThreads();
        logger.info("Server initialization completed");
        Runtime.getRuntime().addShutdownHook(new Thread(this::StopServer, "tyche-shutdown"));
        GRPC.start(this);
    }

    public void StopServer() {
        logger.info("Stopping server");
        this.engine.StopServer();
        this.lb.shutdown();
        this.repo.redis.close();
    }

}
