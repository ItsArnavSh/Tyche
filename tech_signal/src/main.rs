use std::sync::Arc;

use tonic::{Request, Response, Status, transport::Server};

pub mod entity;
pub mod server;
pub mod services;
mod proto {
    tonic::include_proto!("stockrec");
}

use proto::rust_service_server::{RustService, RustServiceServer};
use proto::{
    SendBootSignalRequest, SendBootSignalResponse, SendRollDataRequest, SendRollDataResponse,
};
#[derive(Default, Debug)]
pub struct RustyService {
    server: Arc<server::core::Server>,
}
impl RustyService {
    pub fn new() -> Self {
        Self {
            server: Arc::new(server::core::Server::new("redis://127.0.0.1:6379/")),
        }
    }
}
#[tonic::async_trait]
impl RustService for RustyService {
    async fn send_boot_signal(
        &self,
        request: Request<SendBootSignalRequest>,
    ) -> Result<Response<SendBootSignalResponse>, Status> {
        let data = request.into_inner();
        self.server.boot_loader(data);
        let response = SendBootSignalResponse { status: true };
        Ok(Response::new(response))
    }
    async fn send_roll_data(
        &self,
        request: Request<SendRollDataRequest>,
    ) -> Result<Response<SendRollDataResponse>, Status> {
        request.into_inner().stock
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr = "[::1]:50052".parse()?;
    let service = RustyService::new();
    let shared_service = Arc::clone(&service.server);
    tokio::spawn(async move {
        println!("Starting Calc Server");
        shared_service.start_server().await;
    });
    println!("Starting server at 50052");
    Server::builder()
        .add_service(RustServiceServer::new(service))
        .serve(addr)
        .await?;

    Ok(())
}
