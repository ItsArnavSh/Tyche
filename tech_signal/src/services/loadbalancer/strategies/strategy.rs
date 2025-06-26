pub struct Strategy {
    pub functionid: u32,
    pub candle_size: u64, //In milliseconds
    pub alert: bool,
    pub alert_candle_size: u64, //0 if alert is set to false
}
