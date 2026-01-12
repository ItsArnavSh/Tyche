use std::time::{Duration, Instant};

pub struct BotClock {
    start: Instant,
}

impl BotClock {
    /// Initializes the clock and starts ticking
    pub fn new() -> Self {
        BotClock {
            start: Instant::now(),
        }
    }

    /// Returns time elapsed since start, in milliseconds
    pub fn now_ms(&self) -> u64 {
        self.start.elapsed().as_millis() as u64
    }

    /// Returns time elapsed since start, as Duration
    pub fn elapsed(&self) -> Duration {
        self.start.elapsed()
    }

    /// Returns time elapsed since start, in nanoseconds (for very high-precision needs)
    pub fn now_ns(&self) -> u128 {
        self.start.elapsed().as_nanos()
    }
}
