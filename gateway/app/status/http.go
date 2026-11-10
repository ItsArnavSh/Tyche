package status

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"time"
)

// SnapshotFunc builds a fresh status document on demand.
type SnapshotFunc func() Snapshot

// Handler serves the read-only status JSON. CORS is enabled because the
// dashboard is served from a different origin during local development and in
// Docker Compose.
func Handler(snapshot SnapshotFunc) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	statusHandler := func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		writeJSON(w, http.StatusOK, snapshot())
	}
	mux.HandleFunc("/api/status", statusHandler)
	mux.HandleFunc("/status", statusHandler)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{
			"service": "tyche-gateway",
			"status":  "/api/status",
			"health":  "/healthz",
		})
	})
	return withCORS(mux)
}

// HTTPServer wraps the standard library server with graceful shutdown.
type HTTPServer struct {
	addr     string
	server   *http.Server
	listener net.Listener
}

func NewHTTPServer(addr string, snapshot SnapshotFunc) *HTTPServer {
	handler := Handler(snapshot)
	return &HTTPServer{
		addr: addr,
		server: &http.Server{
			Addr:              addr,
			Handler:           handler,
			ReadHeaderTimeout: 5 * time.Second,
			IdleTimeout:       60 * time.Second,
		},
	}
}

// Run listens until the context is cancelled. The listener is created
// explicitly so address/port errors surface immediately instead of in a
// background goroutine.
func (s *HTTPServer) Run(ctx context.Context) error {
	listener, err := net.Listen("tcp", s.addr)
	if err != nil {
		return err
	}
	s.listener = listener
	log.Printf("[status] listening on http://%s/api/status", listener.Addr())

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := s.server.Shutdown(shutdownCtx); err != nil {
			log.Printf("[status] shutdown: %v", err)
		}
	}()

	err = s.server.Serve(listener)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Cache-Control")
		w.Header().Set("Cache-Control", "no-store")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, code int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("[status] encode response: %v", err)
	}
}
