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
    pub repo: Repository,
    pub lb: LoadBalancer,
    pub activestocks: ActiveStocks,
}

impl Server {
    pub fn new(url: &str) -> Self {
        println!("Connection with Reddis Established");
        let stocks = ActiveStocks::new(BotClock::new());
        Server {
            repo: Repository::new(url),
            lb: LoadBalancer::new(),
            activestocks: stocks,
            scheduler: Arc::new(Mutex::new(UBee::new(&stocks))),
        }
    }

    pub async fn start_server(&mut self) {
        let tickers = vec![];

        let no_threads = rayon::current_num_threads();
        println!("Starting server with {} threads", no_threads);
        rayon::scope(|s| {
            for i in 0..no_threads {
                let ubee: Arc<UBee> = Arc::clone(&self.scheduler);
                s.spawn(move |_| {
                    loop {
                        let tasks = { ubee.give_jobs() };
                        //To-do: Process the task one by one here
                        for task in &tasks {
                            //task.ticker;
                            let funcs = self.lb.give_funcs(
                                task.ticker,
                                self.activestocks.boot_check(task.ticker.0, task.ticker.1),
                            );
                            //Computing the functions one by one
                            for func in funcs {
                                func(self.repo, task.ticker.0);
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
        for ticker in stocks.hist {
            let name = ticker.name;
            for candles in ticker.series {
                self.repo
                    .cache
                    .put_candle(&name, candles.size(), candles.val);
            }
        }
    }

    pub fn roll_loader(&mut self, stocks: SendRollDataRequest) -> SendRollDataResponse {
        let mut missing: Vec<StockSeries> = vec![];
        for stock in stocks.stock {
            let name = stock.name;
            for val in stock.vals {
                let size = val.size();
                if self.activestocks.stale_check(name, size) {
                    missing.push(StockSeries {
                        name: name.clone(),
                        size: size as i32,
                    });
                } else {
                    self.repo.cache.push_candle(&name, size, val.val.unwrap());
                    let ubee = Arc::clone(&self.scheduler);
                    let mut ubee = ubee.lock().unwrap();
                    ubee.update_heap(newvals);
                }
            }
        }
        SendRollDataResponse { missing: missing }
    }
    pub fn update_data(&self, stocks: Vec<(String, CandleSize)>) {
        let ubee = Arc::clone(&self.scheduler);
        let mut bee = ubee.lock().unwrap();
        bee.update_heap(stocks);
    }
}
