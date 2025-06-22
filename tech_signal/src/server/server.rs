use crate::services::{redis::redis::RedisConn, scheduler::ubee::UBee};
use std::sync::{Arc, Mutex};

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

    pub fn start_server(&self) {
        let no_threads = rayon::current_num_threads();

        rayon::scope(|s| {
            for _ in 0..no_threads {
                let ubee = Arc::clone(&self.scheduler);
                s.spawn(move |_| {
                    let mut bee = ubee.lock().unwrap();
                    let tasks = bee.give_jobs();
                    println!("Got {} tasks", tasks.len());
                });
            }
        });
    }
}

