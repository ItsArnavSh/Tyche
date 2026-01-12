use std::collections::VecDeque;

use crate::proto::{CandleSize, StockValue};
use dashmap::DashMap;

pub struct Cache {
    var_cache: DashMap<(String, String), f64>,
    candles_cache: DashMap<(String, CandleSize), VecDeque<StockValue>>,
}

impl Cache {
    pub fn new() -> Self {
        Cache {
            var_cache: DashMap::new(),
            candles_cache: DashMap::new(),
        }
    }

    /// Push a new candle to the cache for a given (ticker, timeframe)
    /// Respects a fixed max length (optional, configurable later)
    pub fn push_candle(&self, ticker: &str, candle_size: CandleSize, candle: StockValue) {
        let key = (ticker.to_string(), candle_size);
        let mut vec = self.candles_cache.entry(key).or_insert_with(VecDeque::new);
        vec.push_back(candle);
        vec.remove(0); // remove oldest
    }
    pub fn put_candle(&self, ticker: &str, candle_size: CandleSize, vals: Vec<StockValue>) {
        let key = (ticker.to_string(), candle_size);
        self.candles_cache.insert(key, VecDeque::from(vals));
    }
    //Todo: Add the retreival functions too
    /// Set variable cache (e.g., MA_15, RSI)
    pub fn set_var(&self, ticker: &str, var: &str, value: f64) {
        self.var_cache
            .insert((ticker.to_string(), var.to_string()), value);
    }

    /// Get variable cache
    pub fn get_var(&self, ticker: &str, var: &str) -> Option<f64> {
        self.var_cache
            .get(&(ticker.to_string(), var.to_string()))
            .map(|v| *v)
    }
    /// Clear the entire cache (candles + vars)
    pub fn clear_all(&self) {
        self.candles_cache.clear();
        self.var_cache.clear();
    }
}
