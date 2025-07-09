use dashmap::DashMap;

use crate::{
    proto::CandleSize::{self},
    services::timer::timer::BotClock,
};

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
pub struct StockVal {
    time: u64,
    bootmode: bool,
}
pub struct ActiveStocks {
    stocks: DashMap<(String, CandleSize), StockVal>,
    timer: BotClock,
}

impl ActiveStocks {
    pub fn new(timer: BotClock) -> Self {
        Self {
            stocks: DashMap::new(),
            timer,
        }
    }

    pub fn boot_check(&self, name: String, size: CandleSize) -> bool {
        self.stocks.get(&(name, size)).unwrap().bootmode
    }
    pub fn stale_check(&mut self, name: String, size: CandleSize) -> bool {
        let now = self.timer.now_ms();

        match self.stocks.get(&(name.clone(), size)) {
            Some(last_time) => {
                let elapsed = now - last_time.time;
                let threshold = size.duration_ms() * 2;

                if elapsed > threshold {
                    // Mark as stale
                    println!(
                        "[STALE] {} (elapsed {}ms > threshold {}ms)",
                        name, elapsed, threshold
                    );
                    return true;
                }

                // Fresh, update timestamp
                self.stocks.insert(
                    (name, size).clone(),
                    StockVal {
                        time: now,
                        bootmode: false,
                    },
                );
                false
            }
            None => {
                // New key → implicitly stale
                self.stocks.insert(
                    (name, size).clone(),
                    StockVal {
                        time: now,
                        bootmode: false,
                    },
                );
                true
            }
        }
    }
}
