use crate::{
    proto::{
        CandleSize, SendBootSignalRequest, SendRollDataRequest, SendRollDataResponse, StockSeries,
        StockValue,
    },
    server::stock_handler::ActiveStocks,
    services::{
        internal::{redis::redis::RedisConn, repository::Repository},
        loadbalancer::lb::LoadBalancer,
        scheduler::ubee::UBee,
        timer::timer::BotClock,
    },
};
use std::sync::{Arc, Mutex};
pub struct Server {
    pub scheduler: Arc<Mutex<UBee>>,
    pub repo: Arc<Repository>,
    pub lb: Arc<Mutex<LoadBalancer>>,
    pub activestocks: Arc<Mutex<ActiveStocks>>,
}

impl Server {
    pub fn new(url: &str) -> Self {
        println!("Connection with Reddis Established");
        let stocks = Arc::new(Mutex::new(ActiveStocks::new(BotClock::new())));
        Server {
            repo: Arc::new(Repository::new(url)),
            lb: Arc::new(Mutex::new(LoadBalancer::new())),
            activestocks: Arc::clone(&stocks),
            scheduler: Arc::new(Mutex::new(UBee::new(Arc::clone(&stocks)))),
        }
    }

    pub async fn start_server(&mut self) {
        let no_threads = rayon::current_num_threads();
        let lb = Arc::clone(&self.lb);
        let activestocks = Arc::clone(&self.activestocks);
        let repo = self.repo.clone();
        println!("Starting server with {} threads", no_threads);
        rayon::scope(|s| {
            for _ in 0..no_threads {
                let ubee = Arc::clone(&self.scheduler);
                let lb = Arc::clone(&lb); // move safe clone
                let activestocks = Arc::clone(&activestocks);
                let repo = repo.clone(); // Arc or Clone

                s.spawn(move |_| {
                    loop {
                        let mut ubee = ubee.lock().unwrap();
                        let tasks = ubee.give_jobs();

                        for task in &tasks {
                            let funcs = lb.lock().unwrap().give_funcs(
                                task.ticker.clone(),
                                activestocks
                                    .lock()
                                    .unwrap()
                                    .boot_check(task.ticker.0.clone(), task.ticker.1),
                            );

                            for func in funcs {
                                func(&repo, task.ticker.0.clone());
                            }
                        }

                        if tasks.is_empty() {
                            std::thread::sleep(std::time::Duration::from_millis(1000));
                        }
                    }
                });
            }
        });
    }
    pub fn boot_loader(&self, stocks: SendBootSignalRequest) {
        let mut roll_these: Vec<(String, CandleSize)> = vec![];
        for ticker in stocks.hist {
            let name = ticker.name;
            for candles in ticker.series {
                self.repo
                    .cache
                    .put_candle(&name, candles.size(), candles.clone().val);
                roll_these.push((name.clone(), candles.size()));
            }
        }
        self.update_data(roll_these, true);
    }

    pub fn roll_loader(&mut self, stocks: SendRollDataRequest) -> SendRollDataResponse {
        let mut missing: Vec<StockSeries> = vec![];
        let mut roll_these: Vec<(String, CandleSize)> = vec![];
        for stock in stocks.stock {
            let name = stock.name;
            for val in stock.vals {
                let size = val.size();
                if self
                    .activestocks
                    .lock()
                    .unwrap()
                    .stale_check(name.clone(), size)
                {
                    missing.push(StockSeries {
                        name: name.clone(),
                        size: size as i32,
                    });
                } else {
                    self.repo.cache.push_candle(&name, size, val.val.unwrap());
                    roll_these.push((name.clone(), size));
                }
            }
        }

        self.update_data(roll_these, false);
        SendRollDataResponse { missing: missing }
    }
    pub fn update_data(&self, stocks: Vec<(String, CandleSize)>, boot: bool) {
        let ubee: Arc<Mutex<UBee>> = Arc::clone(&self.scheduler);
        let mut bee = ubee.lock().unwrap();
        bee.update_heap(stocks, boot);
    }
}
