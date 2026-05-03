package org.Tyche.src.core.engine.Workers;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.Tyche.src.core.engine.Scheduler.LoadBalancer;
import org.Tyche.src.core.engine.Scheduler.Scheduler;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.StrategyEntity.Context;
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
      this.r = new StartParams(repo, null);
   }

   public void Monitor() {
      ArrayList<Integer>[] core_status;

      try {
         for (;;) {

            Thread.sleep(1);
         }
      } catch (Exception e) {
      }
   }

   public void BootThreads() {
      this.cores = Runtime.getRuntime().availableProcessors();
      this.executor = Executors.newFixedThreadPool(cores);
      repo.redis.set("monitor:cores_no", String.valueOf(cores));
      System.out.print(cores);

      // Add this globally
      Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
         System.err.println("Thread " + thread.getName() + " crashed!");
         throwable.printStackTrace();
      });

      for (int i = 0; i < cores; i++) {
         var worker_id = i;
         Runnable process = () -> {
            try {
               ThreadTask(worker_id);
            } catch (Throwable t) {
               System.err.println("Worker " + worker_id + " died with: " + t.getMessage());
               t.printStackTrace();
            }
         };
         executor.submit(process);
      }
   }

   private void ThreadTask(int worker_id) {
      try {
         logger.debug("Worker " + worker_id + " is Booting");

         if (org.Tyche.Config.SIMULATION_MODE) {
            try {
               Thread.sleep(2000);
            } catch (Exception e) {
            }
            logger.debug("Worker " + worker_id + " is Booted");
         }
         for (;;) {

            // logger.info("Asking for a job");
            var jobs = this.scheduler.give_jobs();

            if (jobs.length == 0) {
               // logger.debug("No Jobs available");
               try {
                  Thread.sleep(5000);
               } catch (Exception e) {
               }
            }
            for (var job : jobs) {
               if (job.equals(null)) {
                  try {
                     // logger.debug("No Jobs available");
                     Thread.sleep(5000);
                  } catch (Exception e) {
                  }
                  continue;
               }
               // this.logger.debug("Task assigned: " + job.name);
               var is_boot = this.stock_handler.boot_check(job);
               // this.logger.debug("Boot check: " + is_boot);
               var funcs = this.load_balancer.give_funcs(job, is_boot);
               if (funcs == null || funcs.size() == 0) {
                  // System.out.println("No funcs");
                  try {
                     // logger.debug("No Jobs available");
                     Thread.sleep(1000);
                  } catch (Exception e) {
                  }
                  continue;
               }
               // this.logger.debug("Func size: " + funcs.size());
               for (var func : funcs) {
                  System.out.println(
                        "Running " + func.toString() + " On " + job.name + " " + job.size + " " + " Worker: "
                              + worker_id);
                  r.context = new Context(job);
                  System.out.println("Running " + (is_boot ? "roll" : "boot"));
                  func.accept(r);

               }

               this.stock_handler.mark_booted(job);

               logger.info("Task Done");
            }
         }
      } catch (Exception e) {
         System.out.println("The thread lowkey crashed " + e.getMessage());
      }
   }

   public void StopServer() {
      this.executor.shutdown();
   }

   public void boot_loader(BootRequest breq) {
      // System.out.println("Received a Boot Request");
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

      // System.out.println("Received a roll request");
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
