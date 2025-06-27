use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::{Duration, SystemTime},
};

use tokio::{spawn, sync::Mutex, time::sleep};

use crate::services::loadbalancer::strategies::strategy::{Odin, Strategy};

pub struct LoadBalancer {
    pub active_tasks: HashMap<String, Vec<u32>>,
    pub time_queue: VecDeque<TimedTask>,
    strategies: Vec<Strategy>,
    odin: Odin,
}

impl LoadBalancer {
    pub fn new() -> Arc<Mutex<Self>> {
        Arc::new(Mutex::new(Self {
            active_tasks: HashMap::new(),
            time_queue: VecDeque::new(),
            odin: Odin::new(),
            strategies: vec![], //Todo: Either hardcode here or fetch from config
        }))
    }
    pub fn load_all(&mut self, tickers: Vec<String>) {
        let straategies = self.strategies.clone();
        for ticker in &tickers {
            for strat in &self.strategies {
                self.add_to_queue(ticker, strat);
            }
        }
    }
    pub fn start_process(&self, lb: Arc<Mutex<Self>>) {
        let lb = lb.clone();
        spawn(async move {
            LoadBalancer::queue_to_dict(lb).await;
        });
    }
    pub fn add_to_queue(&mut self, ticker: &String, strat: &Strategy) {
        let time = {
            if strat.alert {
                strat.alert_candle_size
            } else {
                strat.candle_size
            }
        };
        let new_time = add_time(time);
        self.time_queue.push_back(TimedTask {
            run_at: new_time,
            ticker: ticker.clone(),
            funcid: strat.functionid,
        });
    }
    async fn queue_to_dict(lb: Arc<Mutex<Self>>) {
        loop {
            let task_opt = {
                let mut this = lb.lock().await;
                this.time_queue.pop_front()
            };

            if let Some(top) = task_opt {
                {
                    let mut this = lb.lock().await;
                    this.add_to_dict(top.ticker, top.funcid);
                }
            } else {
                sleep(Duration::from_secs(1)).await; //Polling mode
                continue;
            }

            let maybe_next = {
                let this = lb.lock().await;
                this.time_queue.front().cloned()
            };

            match maybe_next {
                Some(top) => sleep_until(top.run_at).await,
                None => sleep(Duration::from_secs(1)).await,
            }
        }
    }

    fn add_to_dict(&mut self, ticker: String, func_id: u32) {
        self.active_tasks
            .entry(ticker)
            .or_insert(Vec::new())
            .push(func_id);
    }
}

#[derive(Clone)]
pub struct TimedTask {
    pub run_at: SystemTime,
    pub ticker: String,
    pub funcid: u32,
}

async fn sleep_until(target: SystemTime) {
    if let Ok(duration) = target.duration_since(SystemTime::now()) {
        sleep(duration).await;
    }
}
fn add_time(duration: u64) -> SystemTime {
    let duration = Duration::from_millis(duration);
    SystemTime::now() + duration
}
