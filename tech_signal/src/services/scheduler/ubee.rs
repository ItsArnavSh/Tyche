use crate::proto::StockValue;
#[derive(Debug)]
pub struct Block {
    stock: StockValue,
    priority: i32,
}
pub struct UBee {
    store: Vec<Block>,
    core_count: usize,
}
impl UBee {
    pub fn new() -> Self {
        UBee {
            store: vec![],
            core_count: rayon::current_num_threads(),
        }
    }
}
