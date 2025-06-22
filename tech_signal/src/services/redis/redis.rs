use redis::{Commands, Connection, RedisResult};

pub struct RedisConn {
    pub conn: Connection,
}
pub struct Signal{
   ticker:&str,
   strategy:&str,
   decision:&str
    
}
impl RedisConn {
    pub fn new(url: &str) -> redis::RedisResult<Self> {
        let client = redis::Client::open(url).unwrap();
        let conn = client.get_connection()?;
        Ok(RedisConn { conn })
    }
    pub fn set_cache(&mut self, ticker: &str, strategy: &str, value: f32) -> RedisResult<()> {
        self.conn.set(format!("{ticker}~{strategy}"), value)
    }
    pub fn get_cache(&mut self, ticker: &str, strategy: &str) -> RedisResult<f32> {
        self.conn.get(format!("{ticker}~{strategy}"))
    }
    pub fn push_signal(&mut self, ticker: &str, strategy: &str, decision: &str) -> RedisResult<()> {
        let signal = 
        let data = serde_json::to_string(&signal).unwrap();

        self.conn.lpush("stocksignal", data)
    }
}
