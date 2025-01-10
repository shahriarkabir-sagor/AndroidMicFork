use adb_client::ADBServer;
use anyhow::Result;
use rtrb::Producer;

use crate::streamer::tcp_streamer;

use super::{tcp_streamer::TcpStreamer, ConnectError, Status, StreamerTrait};

pub struct AdbStreamer {
    tcp_streamer: TcpStreamer,
}

fn start_reverse_proxy(port: u16) -> Result<(), ConnectError> {
    let mut server = ADBServer::default();
    let mut device = server.get_device().map_err(ConnectError::AdbFailed)?;

    // remove all reverse proxy
    device
        .reverse_remove_all()
        .map_err(ConnectError::AdbFailed)?;

    let remote = "tcp:6000".to_string();
    let local = format!("udp:{port}");

    info!("starting adb streamer on remote: {remote}, local: {local}");

    // start reverse proxy
    device
        .reverse(remote, local)
        .map_err(ConnectError::AdbFailed)?;

    Ok(())
}

fn stop_reverse_proxy() -> Result<(), ConnectError> {
    let mut server = ADBServer::default();
    let mut device = server.get_device().map_err(ConnectError::AdbFailed)?;

    // remove all reverse proxy
    device
        .reverse_remove_all()
        .map_err(ConnectError::AdbFailed)?;

    Ok(())
}

pub async fn new(producer: Producer<u8>) -> Result<AdbStreamer, ConnectError> {
    let tcp_streamer = tcp_streamer::new(str::parse("127.0.0.1").unwrap(), producer).await?;

    start_reverse_proxy(tcp_streamer.port)?;

    Ok(AdbStreamer { tcp_streamer })
}

impl StreamerTrait for AdbStreamer {
    async fn next(&mut self) -> Result<Option<Status>, ConnectError> {
        self.tcp_streamer.next().await
    }

    fn set_buff(&mut self, buff: Producer<u8>) {
        self.tcp_streamer.set_buff(buff)
    }

    fn status(&self) -> Option<Status> {
        self.tcp_streamer.status()
    }
}
