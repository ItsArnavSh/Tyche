use crate::proto::CandleSize::{self, *};
use crate::services::internal::repository::Repository;
use std::collections::HashMap;
// Function signature type alias
pub type StratFunc = fn(&Repository, String);

// Wrap both indicator and boot functions
#[derive(Clone)]
struct StrategyPair {
    indicator: StratFunc,
    boot: StratFunc,
}

pub struct Odin {
    strategies: HashMap<CandleSize, Vec<StrategyPair>>,
}

impl Odin {
    pub fn new() -> Self {
        let mut map: HashMap<CandleSize, Vec<StrategyPair>> = HashMap::new();

        // Sample population for each candle size
        map.insert(
            Sec5,
            vec![
                StrategyPair {
                    indicator: ma_cross_5s,
                    boot: boot_ma_cross_5s,
                },
                StrategyPair {
                    indicator: rsi_check_5s,
                    boot: boot_rsi_check_5s,
                },
            ],
        );

        map.insert(
            Min15,
            vec![StrategyPair {
                indicator: bollinger_bands_15m,
                boot: boot_bollinger_bands_15m,
            }],
        );

        Odin { strategies: map }
    }

    /// Fetch all functions (indicator or boot) for a given candle size
    pub fn get_funcs(&self, candle: CandleSize, boot_mode: bool) -> Vec<StratFunc> {
        self.strategies
            .get(&candle)
            .map(|pairs| {
                pairs
                    .iter()
                    .map(|pair| if boot_mode { pair.boot } else { pair.indicator })
                    .collect()
            })
            .unwrap_or_default()
    }
}

//
// Sample Functions
//

// --- 5s ---
fn ma_cross_5s(_: &Repository, _: String) {}
fn boot_ma_cross_5s(_: &Repository, _: String) {}
fn rsi_check_5s(_: &Repository, _: String) {}
fn boot_rsi_check_5s(_: &Repository, _: String) {}

// --- 15m ---
fn bollinger_bands_15m(_: &Repository, _: String) {}
fn boot_bollinger_bands_15m(_: &Repository, _: String) {}
