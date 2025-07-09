use std::{
    collections::BinaryHeap,
    sync::Arc,
    time::{Duration, SystemTime},
};

use dashmap::DashMap;
use tokio::{spawn, sync::Mutex, time::sleep};

use crate::{
    proto::CandleSize,
    services::loadbalancer::strategies::strategy::{Odin, StratFunc},
};
pub struct LoadBalancer {
    pub active_tasks: DashMap<String, Vec<CandleSize>>, //As in these candles of these tickers need to be processed
    pub time_queue: BinaryHeap<TimedTask>,
    odin: Odin,
}

impl LoadBalancer {
    pub fn new() -> Self {
        Self {
            active_tasks: DashMap::new(),
            time_queue: BinaryHeap::new(),
            odin: Odin::new(),
        }
    }
    pub fn start_process(&self, lb: Arc<Mutex<Self>>) {
        let lb = lb.clone();
        spawn(async move {
            LoadBalancer::queue_to_dict(lb).await;
        });
    }
    pub fn give_funcs(&mut self, ticker: (String, CandleSize), boot: bool) -> Vec<StratFunc> {
        let candle_size = self.active_tasks.get(&ticker.0);
        match candle_size {
            Some(_) => return self.odin.get_funcs(ticker.1, boot),
            None => return vec![],
        }
    }
    pub fn add_to_queue(&mut self, ticker: String, series: CandleSize) {
        self.time_queue.push(TimedTask {
            run_at: add_time(series.duration_ms()),
            ticker: (ticker, series),
        });
    }
    pub async fn queue_to_dict(lb: Arc<Mutex<Self>>) {
        loop {
            let task_opt = {
                let mut this = lb.lock().await;
                this.time_queue.pop()
            };

            if let Some(top) = task_opt {
                {
                    let mut this = lb.lock().await;
                    this.add_to_dict(top.ticker.0, top.ticker.1);
                }
            } else {
                sleep(Duration::from_secs(1)).await; //Polling mode
                continue;
            }

            let maybe_next = {
                let this = lb.lock().await;
                this.time_queue.peek().cloned()
            };

            match maybe_next {
                Some(top) => sleep_until(top.run_at).await,
                None => sleep(Duration::from_secs(1)).await,
            }
        }
    }

    fn add_to_dict(&mut self, ticker: String, series: CandleSize) {
        self.active_tasks
            .entry(ticker)
            .or_insert(Vec::new())
            .push(series);
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct TimedTask {
    pub run_at: SystemTime,
    pub ticker: (String, CandleSize),
}
impl Ord for TimedTask {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        other.run_at.cmp(&self.run_at)
    }
}
impl PartialOrd for TimedTask {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
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
