pub struct Strategy {
    pub functionid: u32,
    pub candle_size: u64, //In milliseconds
    pub alert: bool,
    pub alert_candle_size: u64, //0 if alert is set to false
}

pub struct Odin {
    indicators: Vec<fn(&Odin, String)>,
    startup_functions: Vec<fn(&Odin, String)>,
}

impl Odin {
    pub fn new() -> Self {
        return Odin {
            indicators: vec![Self::moving_average_crossover],
            startup_functions: vec![Self::boot_moving_average_crossover],
        };
    }
    pub fn get_by_id(&self, id: usize) -> fn(&Odin, String) {
        *self.indicators.get(id).unwrap()
    }
    pub fn boot_func(&self, id: usize) -> fn(&Odin, String) {
        *self.startup_functions.get(id).unwrap()
    }

    pub fn moving_average_crossover(&self, _: String) {}
    pub fn boot_moving_average_crossover(&self, _: String) {}
}
