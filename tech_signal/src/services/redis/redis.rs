use core::fmt;
use redis::{Commands, Connection, RedisResult};
use serde::Serialize;

pub struct RedisConn {
    pub conn: Option<Connection>,
}

impl Default for RedisConn {
    fn default() -> Self {
        RedisConn { conn: None }
    }
}

impl fmt::Debug for RedisConn {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RedisConn")
            .field("conn", &"[REDACTED redis::Connection]")
            .finish()
    }
}

#[derive(Serialize)]
pub struct Signal {
    ticker: String,
    strategy: String,
    decision: String,
}

impl RedisConn {
    pub fn new(url: &str) -> RedisResult<Self> {
        let client = redis::Client::open(url)?;
        let conn = client.get_connection()?;
        Ok(RedisConn { conn: Some(conn) })
    }

    pub fn set_cache(&mut self, ticker: &str, strategy: &str, value: f32) -> RedisResult<()> {
        match &mut self.conn {
            Some(con) => con.set(format!("{ticker}~{strategy}"), value),
            None => Err(redis::RedisError::from((
                redis::ErrorKind::IoError,
                "Redis connection not initialized",
            ))),
        }
    }

    pub fn get_cache(&mut self, ticker: &str, strategy: &str) -> RedisResult<f32> {
        match &mut self.conn {
            Some(con) => con.get(format!("{ticker}~{strategy}")),
            None => Err(redis::RedisError::from((
                redis::ErrorKind::IoError,
                "Redis connection not initialized",
            ))),
        }
    }

    pub fn push_signal(&mut self, ticker: &str, strategy: &str, decision: &str) -> RedisResult<()> {
        let signal = Signal {
            ticker: ticker.to_string(),
            strategy: strategy.to_string(),
            decision: decision.to_string(),
        };

        let data = serde_json::to_string(&signal).unwrap();

        match &mut self.conn {
            Some(con) => con.lpush("stocksignal", data),
            None => Err(redis::RedisError::from((
                redis::ErrorKind::IoError,
                "Redis connection not initialized",
            ))),
        }
    }
}

