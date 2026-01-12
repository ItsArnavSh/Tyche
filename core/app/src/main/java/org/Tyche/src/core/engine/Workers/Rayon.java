package org.Tyche.src.core.engine.Workers;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.Tyche.src.core.engine.Scheduler.LoadBalancer;
import org.Tyche.src.core.engine.Scheduler.Scheduler;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.internal.cache.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//We are using platform threads in java
public class Rayon {
   public int cores;
   LoadBalancer load_balancer;
   StockHandler stock_handler;
   Scheduler scheduler;
   Repository repo;
   StartParams r;

   private final Logger logger = LoggerFactory.getLogger(Rayon.class);;
   ExecutorService executor;

   public Rayon(LoadBalancer lb, StockHandler sh, Scheduler sch, Repository repo) {
      this.load_balancer = lb;
      this.stock_handler = sh;
      this.scheduler = sch;
      this.repo = repo;
   }

   public void BootThreads() {
      Runnable process = () -> {
         // this.logger.info("Core Spawned");
         ThreadTask();
      };
      this.cores = Runtime.getRuntime().availableProcessors();
      this.executor = Executors.newFixedThreadPool(cores);
      System.out.print(cores);
      for (int i = 0; i < cores; i++) {
         executor.submit(process);
      }
   }

   private void ThreadTask() {
      for (;;) {

         // logger.info("Asking for a job");
         var jobs = this.scheduler.give_jobs();
         if (jobs.length == 0) {

            // logger.debug("No Jobs available");
            try {
               Thread.sleep(1000);
            } catch (Exception e) {
            }
         }
         for (var job : jobs) {
            if (job.equals(null)) {
               try {
                  // logger.debug("No Jobs available");
                  Thread.sleep(1000);
               } catch (Exception e) {
               }
               continue;
            }
            // this.logger.debug("Task assigned: " + job.name);
            var is_boot = this.stock_handler.boot_check(job);

            // this.logger.debug("Boot check: " + is_boot);
            var funcs = this.load_balancer.give_funcs(job, is_boot);
            if (funcs == null || funcs.size() == 0) {
               System.out.println("No funcs");
               try {
                  // logger.debug("No Jobs available");
                  Thread.sleep(1000);
               } catch (Exception e) {
               }
               continue;
            }
            // this.logger.debug("Func size: " + funcs.size());
            for (var func : funcs) {
               func.accept(r);
            }

            logger.info("Marking as booted");
            this.stock_handler.mark_booted(job);

            logger.info("Task Done");
         }
      }
   }

   public void StopServer() {
      this.executor.shutdown();
   }

   public void boot_loader(BootRequest breq) {
      System.out.println("In the Boot Loader");
      var roll_these = new ArrayList<PriorityBlock>();
      for (var stock_history : breq.history) {
         var name = stock_history.name;
         for (var candle_series : stock_history.series) {
            var size = candle_series.size;
            PriorityBlock block = new PriorityBlock(size, name);
            this.stock_handler.stale_check(block);
            this.repo.cache.new_candles(block, candle_series.candles);
            roll_these.add(block);

         }

      }
      this.update_data(roll_these, true);
   }

   public ArrayList<PriorityBlock> roll_loader(RollRequest rreq) {

      System.out.println("In the Roll Loader");
      var missing = new ArrayList<PriorityBlock>();
      var roll_these = new ArrayList<PriorityBlock>();
      for (var stock_update : rreq.update) {
         var name = stock_update.name;
         for (var stock : stock_update.val) {
            var size = stock.size;
            var block = new PriorityBlock(size, name);
            var is_stale = this.stock_handler.stale_check(block);
            if (is_stale)
               missing.add(block);
            else {
               this.repo.cache.push_candle(block, stock.candle);
               roll_these.add(block);

            }
         }
      }
      update_data(roll_these, false);
      return missing;
   }

   public void update_data(ArrayList<PriorityBlock> stocks, boolean boot) {
      if (boot) {
         for (var stock : stocks) {
            this.load_balancer.set_boot_ticker(stock.name);
         }
      }
      this.scheduler.UpdateHeap(stocks, boot);
   }
}
