use tonic::{Request, Response, Status, transport::Server};

pub mod server;
pub mod services;
mod proto {
    tonic::include_proto!("stockrec");
}

use proto::rust_service_server::{RustService, RustServiceServer};
use proto::{SendStockDataRequest, SendStockDataResponse};
#[derive(Default, Debug)]
pub struct RustyService;

#[tonic::async_trait]
impl RustService for RustyService {
    async fn send_stock_data(
        &self,
        request: Request<SendStockDataRequest>,
    ) -> Result<Response<SendStockDataResponse>, Status> {
        let stocks = request.into_inner().stocks;

        for stock in stocks {
            println!(
                "Got stock {}: open={}, close={}, high={}, low={}",
                stock.name, stock.open, stock.close, stock.high, stock.low
            );
        }

        let response = SendStockDataResponse { status: true };
        Ok(Response::new(response))
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr = "[::1]:50052".parse()?;
    let service = RustyService::default();
    println!("Starting server at 50052");
    Server::builder()
        .add_service(RustServiceServer::new(service))
        .serve(addr)
        .await?;

    Ok(())
}
