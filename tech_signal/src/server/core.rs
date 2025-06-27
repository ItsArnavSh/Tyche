use crate::{
    proto::StockValue,
    services::{internal::redis::redis::RedisConn, scheduler::ubee::UBee},
};
use std::sync::{Arc, Mutex};
#[derive(Debug, Default)]
pub struct Server {
    pub rediscon: RedisConn,
    pub scheduler: Arc<Mutex<UBee>>,
}

impl Server {
    pub fn new(url: &str) -> Self {
        let redisconn = RedisConn::new(url).unwrap();
        println!("Connection with Reddis Established");
        Server {
            rediscon: redisconn,
            scheduler: Arc::new(Mutex::new(UBee::new())),
        }
    }

    pub async fn start_server(&self) {
        let no_threads = rayon::current_num_threads();
        println!("Starting server with {} threads", no_threads);
        rayon::scope(|s| {
            for i in 0..no_threads {
                let ubee = Arc::clone(&self.scheduler);
                s.spawn(move |_| {
                    loop {
                        let tasks = {
                            let mut bee = ubee.lock().unwrap();
                            bee.give_jobs()
                        };
                        //To-do: Process the task one by one here
                        for task in &tasks {
                            println!(
                                "Worker {} is doing {} at priority {}",
                                i, task.ticker, task.priority
                            );
                            std::thread::sleep(std::time::Duration::from_millis(1000)); //Doing
                            //task
                        }
                        if tasks.is_empty() {
                            std::thread::sleep(std::time::Duration::from_millis(1));
                        }
                    }
                });
            }
        });
    }
    pub fn update_data(&self, stocks: Vec<StockValue>) {
        let ubee = Arc::clone(&self.scheduler);
        let mut bee = ubee.lock().unwrap();
        bee.update_heap(stocks);
    }
}
