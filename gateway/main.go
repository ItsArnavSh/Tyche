package gateway

import (
	"context"
	"fmt"
	"gateway/app/server"
	"log"
)

func main() {
	ctx := context.Background()
	addr := []string{":50051"}
	server, err := server.NewServer(ctx, addr)
	if err != nil {
		fmt.Println(err)
		return
	}
	err = server.BootServer(ctx)
	if err != nil {
		log.Fatal(err)
	}
}
