use crate::services::internal::{cache::cache::Cache, redis::redis::RedisConn};
pub struct Repository {
    pub cache: Cache,
    pub redis: RedisConn,
}
impl Repository {
    pub fn new(url: &str) -> Self {
        let redisres = RedisConn::new(url);
        match redisres {
            Ok(red) => Repository {
                cache: Cache::new(),
                redis: red,
            },
            _ => panic!("Could not establish redis connection"),
        }
    }
}
