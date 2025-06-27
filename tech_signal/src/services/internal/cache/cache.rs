use crate::entity::candle::Candle;
use dashmap::DashMap;

pub struct Cache {
    var_cache: DashMap<(String, String), f64>,
    candles_cache: DashMap<(String, String), Vec<Candle>>,
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
    pub fn push_candle(&self, ticker: &str, tf: &str, candle: Candle, max_len: usize) {
        let key = (ticker.to_string(), tf.to_string());
        let mut vec = self.candles_cache.entry(key).or_insert_with(Vec::new);
        vec.push(candle);
        if vec.len() > max_len {
            vec.remove(0); // remove oldest
        }
    }

    /// Get full vector of candles (cloned)
    pub fn get_candles(&self, ticker: &str, tf: &str) -> Option<Vec<Candle>> {
        self.candles_cache
            .get(&(ticker.to_string(), tf.to_string()))
            .map(|v| v.clone()) // returns a copy
    }

    /// Get the latest candle, if any
    pub fn get_latest_candle(&self, ticker: &str, tf: &str) -> Option<Candle> {
        self.candles_cache
            .get(&(ticker.to_string(), tf.to_string()))
            .and_then(|v| v.last().cloned())
    }

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
