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
        match self.stocks.get(&(name, size)) {
            Some(val) => val.bootmode,
            None => false,
        }
    }
    pub fn mark_booted(&self, name: String, size: CandleSize) {
        if let Some(mut sta) = self.stocks.get_mut(&(name, size)) {
            sta.bootmode = false;
        }
    }

    pub fn stale_check(&mut self, name: String, size: CandleSize) -> bool {
        let now = self.timer.now_ms();
        let key = (name.clone(), size);

        match self.stocks.entry(key.clone()) {
            dashmap::mapref::entry::Entry::Occupied(mut entry) => {
                let elapsed = now - entry.get().time;
                let threshold = size.duration_ms() * 2;

                if elapsed > threshold {
                    entry.remove();
                    true
                } else {
                    entry.get_mut().time = now;
                    false
                }
            }
            dashmap::mapref::entry::Entry::Vacant(entry) => {
                entry.insert(StockVal {
                    time: now,
                    bootmode: true,
                });
                false
            }
        }
    }
}
