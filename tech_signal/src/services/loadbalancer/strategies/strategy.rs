pub struct Strategy {
    pub functionid: u32,
    pub candle_size: u32, //In milliseconds
    pub active_hours: Option<ActiveHours>,
    pub alert: bool,
    pub alert_candle_size: u32, //0 if alert is set to false
}
pub struct ActiveHours {
    pub start_hour: String,
    pub end_hour: String,
}
