use crate::{proto::StockValue, services::scheduler::ubee::util::first_n_primes};
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};
use std::sync::Mutex;
#[derive(Debug, Default, Clone)]
pub struct Block {
    pub ticker: String,
    pub value: f32,
    pub priority: i32,
}
impl Ord for Block {
    fn cmp(&self, other: &Self) -> Ordering {
        self.priority.cmp(&other.priority)
    }
}
impl PartialEq for Block {
    fn eq(&self, other: &Self) -> bool {
        self.priority == other.priority
    }
}
impl Eq for Block {}
impl PartialOrd for Block {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
#[derive(Debug, Default)]
pub struct UBee {
    pub heap: BinaryHeap<Block>,
    pub core_count: usize,
    pub job_counter: usize,
    pub allot_no: usize,
    pub primes: Vec<usize>,
    pub priority_map: HashMap<String, i32>,
    pub lock: Mutex<()>,
}
impl UBee {
    pub fn new() -> Self {
        let thread_no = num_cpus::get();
        println!("Just received count as {}", thread_no);
        UBee {
            heap: BinaryHeap::new(),
            core_count: thread_no,
            job_counter: 0,
            allot_no: 2,
            primes: first_n_primes(thread_no),
            priority_map: HashMap::new(),
            lock: Mutex::new(()),
        }
    }
    pub fn give_jobs(&mut self) -> Vec<Block> {
        let _guard = self.lock.lock().unwrap();
        if self.heap.is_empty() {
            return vec![];
        }
        let mut tasks: Vec<Block> = vec![];
        for _ in 0..self.allot_no {
            let top_element = self.heap.pop();
            match top_element {
                Some(data_val) => tasks.push(data_val),
                None => break,
            }
        }
        println!("Core Count: {}", self.core_count);
        self.job_counter = (self.job_counter + 1) % self.core_count;
        self.allot_no = self.primes[self.job_counter];
        tasks
    }
    pub fn update_heap(&mut self, newvals: Vec<StockValue>) {
        let _guard = self.lock.lock().unwrap();
        //First we check for all the remaining values in heap
        println!("Clearing Stuff");
        loop {
            let block = self.heap.pop();

            match block {
                Some(stock_value) => {
                    println!("{} was left", stock_value.ticker);
                    self.priority_map
                        .entry(stock_value.ticker.clone())
                        .and_modify(|p| *p += 1)
                        .or_insert(101); //Updated priorities
                }
                None => {
                    println!("No blocks remain unseen");
                    break;
                }
            }
        }
        for val in newvals {
            let prio = *self.priority_map.entry(val.name.clone()).or_insert(100);
            self.heap.push(Block {
                ticker: val.name,
                value: val.close,
                priority: prio,
            });
        }
    }
}

mod util {

    pub fn first_n_primes(n: usize) -> Vec<usize> {
        const PRECOMPUTED: [usize; 200] = [
            2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83,
            89, 97, 101, 103, 107, 109, 113, 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179,
            181, 191, 193, 197, 199, 211, 223, 227, 229, 233, 239, 241, 251, 257, 263, 269, 271,
            277, 281, 283, 293, 307, 311, 313, 317, 331, 337, 347, 349, 353, 359, 367, 373, 379,
            383, 389, 397, 401, 409, 419, 421, 431, 433, 439, 443, 449, 457, 461, 463, 467, 479,
            487, 491, 499, 503, 509, 521, 523, 541, 547, 557, 563, 569, 571, 577, 587, 593, 599,
            601, 607, 613, 617, 619, 631, 641, 643, 647, 653, 659, 661, 673, 677, 683, 691, 701,
            709, 719, 727, 733, 739, 743, 751, 757, 761, 769, 773, 787, 797, 809, 811, 821, 823,
            827, 829, 839, 853, 857, 859, 863, 877, 881, 883, 887, 907, 911, 919, 929, 937, 941,
            947, 953, 967, 971, 977, 983, 991, 997, 1009, 1013, 1019, 1021, 1031, 1033, 1039, 1049,
            1051, 1061, 1063, 1069, 1087, 1091, 1093, 1097, 1103, 1109, 1117, 1123, 1129, 1151,
            1153, 1163, 1171, 1181, 1187, 1193, 1201, 1213, 1217, 1223,
        ];

        if n <= PRECOMPUTED.len() {
            PRECOMPUTED[..n].to_vec()
        } else {
            let mut primes = PRECOMPUTED.to_vec();
            let mut candidate = *primes.last().unwrap() + 2;

            while primes.len() < n {
                let mut is_prime = true;
                let sqrt = (candidate as f64).sqrt() as usize + 1;
                for &p in primes.iter() {
                    if p > sqrt {
                        break;
                    }
                    if candidate % p == 0 {
                        is_prime = false;
                        break;
                    }
                }
                if is_prime {
                    primes.push(candidate);
                }
                candidate += 2;
            }

            primes
        }
    }
}
