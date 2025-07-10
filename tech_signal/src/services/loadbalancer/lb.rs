use dashmap::DashMap;
use parking_lot::Mutex;
use std::{
    collections::BinaryHeap,
    sync::Arc,
    time::{Duration, SystemTime},
};
use tokio::{spawn, time::sleep};

use crate::{
    proto::CandleSize,
    services::loadbalancer::strategies::strategy::{Odin, StratFunc},
};
#[derive(Debug)]
pub struct LoadBalancer {
    pub active_tasks: DashMap<String, Vec<CandleSize>>, // Thread-safe, no need for additional mutex
    pub time_queue: Arc<Mutex<BinaryHeap<TimedTask>>>,  // Only the queue needs protection
    odin: Odin,
}

impl LoadBalancer {
    pub fn new() -> Self {
        println!("DEBUG: Creating new LoadBalancer instance");
        Self {
            active_tasks: DashMap::new(),
            time_queue: Arc::new(Mutex::new(BinaryHeap::new())),
            odin: Odin::new(),
        }
    }

    pub fn give_funcs(&self, ticker: (String, CandleSize), boot: bool) -> Vec<StratFunc> {
        println!(
            "DEBUG: give_funcs called with ticker: {}, boot: {}",
            ticker.0, boot
        );

        if let Some(candle_sizes) = self.active_tasks.get(&ticker.0) {
            // Check if the specific candle size is in the active tasks
            if candle_sizes.contains(&ticker.1) {
                println!(
                    "DEBUG: Found active task for ticker: {} with candle size: {:?}, returning functions",
                    ticker.0, ticker.1
                );
                return self.odin.get_funcs(ticker.1, boot);
            }
        }

        println!(
            "DEBUG: No active task found for ticker: {} with candle size: {:?}, returning empty vec",
            ticker.0, ticker.1
        );
        vec![]
    }

    pub fn add_to_queue(&mut self, ticker: String, series: CandleSize) {
        println!(
            "DEBUG: Adding to queue - ticker: {}, series: {:?}",
            ticker, series
        );

        let task = TimedTask {
            run_at: add_time(series.duration_ms()),
            ticker: (ticker, series),
        };
        {
            self.time_queue.lock().push(task);
        }
    }

    fn add_to_dict(&self, ticker: String, series: CandleSize) {
        println!(
            "DEBUG: add_to_dict called with ticker: {}, series: {:?}",
            ticker, series
        );

        self.active_tasks
            .entry(ticker.clone())
            .or_insert_with(Vec::new)
            .push(series);

        println!("DEBUG: Added series to active_tasks for ticker: {}", ticker);
    }

    // Helper method to remove tasks from active_tasks when they're done
    pub fn remove_from_active(&self, ticker: &str, series: CandleSize) {
        println!(
            "DEBUG: Removing from active_tasks - ticker: {}, series: {:?}",
            ticker, series
        );

        if let Some(mut entry) = self.active_tasks.get_mut(ticker) {
            entry.retain(|&candle| candle != series);
            if entry.is_empty() {
                drop(entry); // Release the mutable reference
                self.active_tasks.remove(ticker);
                println!(
                    "DEBUG: Removed ticker {} from active_tasks (no more candles)",
                    ticker
                );
            } else {
                println!(
                    "DEBUG: Removed candle size {:?} from ticker {}",
                    series, ticker
                );
            }
        }
    }

    // Helper method to check if a ticker/candle combination is active
    pub fn is_active(&self, ticker: &str, series: CandleSize) -> bool {
        self.active_tasks
            .get(ticker)
            .map(|candles| candles.contains(&series))
            .unwrap_or(false)
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
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
pub async fn queue_to_dict(lb: Arc<Mutex<LoadBalancer>>) {
    println!("DEBUG: Starting queue_to_dict main loop");
    loop {
        println!("DEBUG: Beginning loop iteration");

        // Check if there are tasks ready to be processed
        let ready_task = {
            let lb_guard = lb.lock();
            let mut queue_guard = lb_guard.time_queue.lock();

            if let Some(top) = queue_guard.peek() {
                if SystemTime::now() >= top.run_at {
                    let task = queue_guard.pop().unwrap();
                    println!("DEBUG: Found ready task in queue: {:?}", task.ticker);
                    Some(task)
                } else {
                    println!("DEBUG: Next task not ready yet, will sleep");
                    None
                }
            } else {
                println!("DEBUG: No tasks in queue");
                None
            }
        }; // Both locks are dropped here

        // Process the ready task
        if let Some(task) = ready_task {
            println!("DEBUG: Processing task for ticker: {}", task.ticker.0);
            let lb_guard = lb.lock();
            lb_guard.add_to_dict(task.ticker.0, task.ticker.1);
        }

        // Determine sleep duration
        let sleep_duration = {
            let lb_guard = lb.lock();
            let queue_guard = lb_guard.time_queue.lock();

            if let Some(next_task) = queue_guard.peek() {
                match next_task.run_at.duration_since(SystemTime::now()) {
                    Ok(duration) => {
                        println!("DEBUG: Next task in {:?}, sleeping until then", duration);
                        duration.min(Duration::from_secs(1)) // Cap at 1 second max
                    }
                    Err(_) => {
                        println!("DEBUG: Next task is overdue, minimal sleep");
                        Duration::from_millis(10) // Very short sleep if overdue
                    }
                }
            } else {
                println!("DEBUG: No next task, sleeping for 1 second");
                Duration::from_secs(1)
            }
        }; // Both locks are dropped here

        sleep(sleep_duration).await;
    }
}
