use crate::{
    proto::CandleSize::{self},
    services::timer::timer::BotClock,
};
use std::collections::HashMap;

impl CandleSize {
    pub fn duration_ms(&self) -> u64 {
        match self {
            CandleSize::Sec5 => 5_000,
            CandleSize::Sec30 => 30_000,
            CandleSize::Min1 => 60_000,
            CandleSize::Min15 => 15 * 60_000,
            CandleSize::Hour1 => 60 * 60_000,
            CandleSize::Day1 => 24 * 60 * 60_000,
        }
    }
}

pub struct ActiveStocks {
    stocks: HashMap<String, u64>,
    timer: BotClock,
}

impl ActiveStocks {
    pub fn new(timer: BotClock) -> Self {
        Self {
            stocks: HashMap::new(),
            timer,
        }
    }

    fn make_key(name: &str, size: &CandleSize) -> String {
        format!("{}:{:?}", name, size)
    }

    pub fn stale_check(&mut self, name: &str, size: CandleSize) -> bool {
        let key = Self::make_key(name, &size);
        let now = self.timer.now_ms();

        match self.stocks.get(&key) {
            Some(&last_time) => {
                let elapsed = now - last_time;
                let threshold = size.duration_ms() * 2;

                if elapsed > threshold {
                    // Mark as stale
                    println!(
                        "[STALE] {} (elapsed {}ms > threshold {}ms)",
                        key, elapsed, threshold
                    );
                    return true;
                }

                // Fresh, update timestamp
                self.stocks.insert(key, now);
                false
            }
            None => {
                // New key → implicitly stale
                self.stocks.insert(key.clone(), now);
                println!("[STALE:NEW] {} first time seen", key);
                false
            }
        }
    }
}
