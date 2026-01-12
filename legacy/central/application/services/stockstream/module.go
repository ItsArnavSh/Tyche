package stockstream
type  StockStreamer interface{
	streamToRust()error
}
