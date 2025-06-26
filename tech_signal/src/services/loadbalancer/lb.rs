use std::{
    collections::{HashMap, VecDeque},
    thread,
    time::{self, SystemTime},
};

pub struct LoadBalancer {
    pub active_tasks: HashMap<String, Vec<u32>>,
    pub time_queue: VecDeque<TimedTask>,
}

impl LoadBalancer {
    pub fn new() -> Self {
        //Trugger the queue_to_dict function

        return LoadBalancer {
            active_tasks: HashMap::new(),
            time_queue: VecDeque::new(),
        };
    }

    //Private Funcs
    fn queue_to_dict(&mut self) {
        loop {
            let top = self.time_queue.pop_front();
            match top {
                Some(top) => self.add_to_dict(top.ticker, top.funcid),
                None => thread::sleep(time::Duration::from_secs(1)),
            }

            let top = self.time_queue.front();
            match top {
                Some(top) => sleep_until(top.run_at),
                None => thread::sleep(time::Duration::from_secs(1)),
            }
        }
    }
    fn add_to_dict(&mut self, ticker: String, func_id: u32) {
        self.active_tasks
            .entry(ticker)
            .or_insert(Vec::new())
            .push(func_id)
    }
}

pub struct TimedTask {
    pub run_at: SystemTime,
    pub ticker: String,
    pub funcid: u32,
}
fn sleep_until(target: SystemTime) {
    match target.duration_since(SystemTime::now()) {
        Ok(duration) => {
            thread::sleep(duration); // Sleep for remaining time
        }
        Err(_) => {
            // Target time is already in the past; return immediately
        }
    }
}
