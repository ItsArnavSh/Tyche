use crate::{
    proto::{
        CandleSize, SendBootSignalRequest, SendRollDataRequest, SendRollDataResponse, StockSeries,
    },
    server::stock_handler::ActiveStocks,
    services::{
        internal::repository::Repository,
        loadbalancer::lb::LoadBalancer,
        scheduler::ubee::{Block, UBee},
        timer::timer::BotClock,
    },
};
use parking_lot;
use std::sync::{Arc, Mutex};
pub struct Server {
    pub scheduler: Arc<Mutex<UBee>>,
    pub repo: Arc<Repository>,
    pub lb: Arc<parking_lot::Mutex<LoadBalancer>>,
    pub activestocks: Arc<Mutex<ActiveStocks>>,
}

impl Server {
    pub fn new(url: &str) -> Self {
        //println!("Connection with Reddis Established");
        let stocks = Arc::new(Mutex::new(ActiveStocks::new(BotClock::new())));
        Server {
            repo: Arc::new(Repository::new(url)),
            lb: Arc::new(parking_lot::Mutex::new(LoadBalancer::new())),

            activestocks: Arc::clone(&stocks),
            scheduler: Arc::new(Mutex::new(UBee::new())),
        }
    }

    pub async fn start_server(&self) {
        let no_threads = rayon::current_num_threads();
        LoadBalancer::new_and_start();
        let activestocks = Arc::clone(&self.activestocks);
        let repo = self.repo.clone();

        let lb = Arc::new(&self.lb);
        //println!("Starting server with {} threads", no_threads);
        rayon::scope(|s| {
            for _ in 0..no_threads {
                let ubee = Arc::clone(&self.scheduler);
                let lb = Arc::clone(&lb); // move safe clone
                let activestocks = Arc::clone(&activestocks);
                let repo = repo.clone(); // Arc or Clone

                s.spawn(move |_| {
                    loop {
                        let mut tasks;
                        {
                            //println!("Waiting for Ubee 1");
                            let mut ubee = ubee.lock().expect("Ubee poisoned 1");
                            tasks = ubee.give_jobs();

                            //println!("Waiting for Ubee Released");
                        }
                        for task in &tasks {
                            let mut funcs;
                            let ticker = task.ticker.clone();

                            // 🔓 First lock: activestocks
                            let is_boot = {
                                //println!("Waiting for Active Stocks 2");
                                let guard = activestocks.lock().expect("Active Stocks Poisoned 2");
                                guard.boot_check(ticker.0.clone(), ticker.1)
                            };

                            // 🔓 Then lock: lb

                            //println!("Waiting for Ubee 3");
                            {
                                funcs = lb.lock().give_funcs(ticker, is_boot);
                            }
                            for func in funcs {
                                func(&repo, task.ticker.0.clone());
                            }
                            {
                                //println!("Waiting for Active 3");
                                let active = activestocks.lock().expect("Active Stocks Poisoned 3");
                                active.mark_booted(task.ticker.0.clone(), task.ticker.1);
                            }
                            {
                                lb.lock().add_to_queue(task.ticker.0.clone(), task.ticker.1);
                            }
                        }

                        if tasks.is_empty() {
                            std::thread::sleep(std::time::Duration::from_millis(500));
                        }
                    }
                });
            }
        });
    }

    pub async fn boot_loader(&self, stocks: SendBootSignalRequest) {
        println!("[BOOT] Boot Signal Loading...");

        let mut roll_these: Vec<(String, CandleSize)> = vec![];

        for (_, ticker) in stocks.hist.into_iter().enumerate() {
            let name = ticker.name;
            //println!("[BOOT] Ticker {}: {}", i + 1, name);

            for (_, candles) in ticker.series.into_iter().enumerate() {
                let size = candles.size();
                {
                    self.activestocks
                        .lock()
                        .unwrap()
                        .stale_check(name.clone(), size);
                }
                self.repo.cache.put_candle(&name, size, candles.clone().val);

                roll_these.push((name.clone(), size));
                {
                    self.lb.lock().add_to_queue(name.clone(), size);
                }
            }
        }

        //println!("[BOOT] Total candles to roll: {}", roll_these.len());
        self.update_data(roll_these, true);
        //println!("[BOOT] Boot data update triggered ✅");
    }

    pub fn roll_loader(&self, stocks: SendRollDataRequest) -> SendRollDataResponse {
        //println!(

        let mut missing: Vec<StockSeries> = vec![];
        let mut roll_these: Vec<(String, CandleSize)> = vec![];

        for (_, stock) in stocks.stock.into_iter().enumerate() {
            let name = stock.name;

            for (_, val) in stock.vals.into_iter().enumerate() {
                let size = val.size();

                let mut is_stale = false;
                {
                    //println!("Waiting for Stale");
                    is_stale = self
                        .activestocks
                        .lock()
                        .expect("Stale Poisoned")
                        .stale_check(name.clone(), size);
                }
                if is_stale {
                    //println!("[ROLL]   --> Marked as MISSING");
                    missing.push(StockSeries {
                        name: name.clone(),
                        size: size as i32,
                    });
                } else {
                    //println!("[ROLL]   --> Data is fresh, pushing to cache");
                    self.repo.cache.push_candle(&name, size, val.val.unwrap());
                    roll_these.push((name.clone(), size));
                }
            }
        }

        self.update_data(roll_these, false);
        //println!("[ROLL] Roll data update triggered ✅");

        SendRollDataResponse { missing }
    }

    pub fn update_data(&self, stocks: Vec<(String, CandleSize)>, boot: bool) {
        for (i, (name, size)) in stocks.iter().enumerate() {
            //println!("[UPDATE]   [{}] {} @ {:?}", i + 1, name, size);
        }
        {
            let ubee: Arc<Mutex<UBee>> = Arc::clone(&self.scheduler);
            //println!("Waiting for lock Ubee");
            let mut bee = ubee.lock().expect("Failed to acquire UBee lock");

            //println!("[UPDATE] Acquired lock on UBee, pushing to update_heap...");
            bee.update_heap(stocks, boot);
        }
        //println!("[UPDATE] Update heap complete ✅");
    }
}
