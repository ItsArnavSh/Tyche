package org.Tyche.src.server;

import org.Tyche.src.core.engine.Scheduler.LoadBalancer;
import org.Tyche.src.core.engine.Scheduler.Scheduler;
import org.Tyche.src.core.engine.Workers.Rayon;
import org.Tyche.src.core.engine.Workers.StockHandler;
import org.Tyche.src.core.producer.Producer;
import org.Tyche.src.core.producer.StockInterface;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.internal.cache.Repository;
import org.Tyche.src.util.BotClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server {
    LoadBalancer lb;
    Scheduler sch;
    BotClock timer;
    StockHandler sh;
    Repository repo;
    Rayon engine;
    Producer prod;
    StockInterface stockapi;
    private static final Logger logger = LoggerFactory.getLogger(Server.class);;

    public Server() {
        this.lb = new LoadBalancer();
        this.sch = new Scheduler(1);
        this.timer = new BotClock();
        this.sh = new StockHandler(timer);
        this.repo = new Repository();
        this.engine = new Rayon(lb, sh, sch, repo);
        this.stockapi = new StockInterface(engine);
    };

    public void StartServer() {
        logger.info("Booting Server");
        this.engine.BootThreads();
        logger.info("Server initialization completed");
        this.stockapi.StockProcess();
    }
}
