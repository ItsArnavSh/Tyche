use crate::proto::CandleSize::{self, *};
use crate::services::internal::repository::Repository;
use std::collections::HashMap;
// Function signature type alias
pub type StratFunc = fn(&Repository, String);

// Wrap both indicator and boot functions
#[derive(Clone, Debug)]
struct StrategyPair {
    indicator: StratFunc,
    boot: StratFunc,
}
#[derive(Debug)]
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

// --- 5s MA Cross Functions ---
fn ma_cross_5s(_: &Repository, ticker: String) {
    println!("[MA_CROSS_5S] Solving MA Cross for {}", ticker);
}

fn boot_ma_cross_5s(_: &Repository, ticker: String) {
    println!("[BOOT_MA_CROSS_5S] Bootstrapping MA Cross for {}", ticker);
}

// --- 5s RSI Functions ---
fn rsi_check_5s(_: &Repository, ticker: String) {
    println!("[RSI_CHECK_5S] Solving RSI Check for {}", ticker);
}

fn boot_rsi_check_5s(_: &Repository, ticker: String) {
    println!("[BOOT_RSI_CHECK_5S] Bootstrapping RSI for {}", ticker);
}

// --- 15m Bollinger Bands Functions ---
fn bollinger_bands_15m(_: &Repository, ticker: String) {
    println!("[BOLLINGER_15M] Solving Bollinger Bands for {}", ticker);
}

fn boot_bollinger_bands_15m(_: &Repository, ticker: String) {
    println!(
        "[BOOT_BOLLINGER_15M] Bootstrapping Bollinger Bands for {}",
        ticker
    );
}
