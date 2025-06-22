use crate::{
    proto::StockValue,
    services::{redis::redis::RedisConn, scheduler::ubee::UBee},
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
        Server {
            rediscon: redisconn,
            scheduler: Arc::new(Mutex::new(UBee::new())),
        }
    }

    pub async fn start_server(&self) {
        let no_threads = rayon::current_num_threads();
        println!("Starting server with {} threads", no_threads);
        rayon::scope(|s| {
            for _ in 0..no_threads {
                let ubee = Arc::clone(&self.scheduler);
                s.spawn(move |_| {
                    loop {
                        let tasks = {
                            let mut bee = ubee.lock().unwrap();
                            bee.give_jobs()
                        };
                        println!("Got {} tasks", tasks.len());
                        //To-do: Process the task one by one here
                        if tasks.is_empty() {
                            std::thread::sleep(std::time::Duration::from_millis(100));
                        }
                    }
                });
            }
        });
    }
    pub fn update_data(&self, stocks: Vec<StockValue>) {}
}
