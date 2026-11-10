package zerodha

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gateway/app/util/entity"
)

const instrumentsCSV = `instrument_token,exchange_token,tradingsymbol,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
123,123,RELIANCE,0,,0,0.05,1,EQ,NSE,NSE
456,456,TCS,0,,0,0.05,1,EQ,NSE,NSE
`

const historyPayload = `{"status":"success","data":{"candles":[
["2026-11-07T09:15:00+0530",100,101,99,100.5,1000],
["2026-11-07T09:16:00+0530",100.5,102,100,101.5,1200]
]}}`

func kiteServer(t *testing.T) (*httptest.Server, *int) {
	t.Helper()
	historyCalls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/instruments":
			fmt.Fprint(w, instrumentsCSV)
		case strings.HasPrefix(r.URL.Path, "/instruments/historical/"):
			historyCalls++
			if got := r.Header.Get("Authorization"); got != "token key:access" {
				t.Errorf("authorization = %q", got)
			}
			if !strings.Contains(r.URL.Path, "/123/") && !strings.Contains(r.URL.Path, "/456/") {
				t.Errorf("unexpected token path %s", r.URL.Path)
			}
			fmt.Fprint(w, historyPayload)
		case r.URL.Path == "/quote/ltp":
			if got := r.Header.Get("Authorization"); got != "token key:access" {
				t.Errorf("authorization = %q", got)
			}
			payload := map[string]any{"status": "success", "data": map[string]any{
				"NSE:RELIANCE": map[string]any{"instrument_token": 123, "last_price": 1333.5},
				"NSE:TCS":      map[string]any{"instrument_token": 456, "last_price": 4111.0},
			}}
			json.NewEncoder(w).Encode(payload)
		default:
			http.NotFound(w, r)
		}
	}))
	return server, &historyCalls
}

func newKiteProducer(t *testing.T, server *httptest.Server) *Producer {
	t.Helper()
	p, err := New(Options{
		Symbols:     []string{"RELIANCE", "TCS"},
		BaseURL:     server.URL,
		APIKey:      "key",
		AccessToken: "access",
		Exchange:    "NSE",
		Timeout:     2 * time.Second,
		Retries:     1,
		BootCandles: 10,
		RollEvery:   time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestZerodhaBootUsesHistoricalEndpoint(t *testing.T) {
	server, historyCalls := kiteServer(t)
	defer server.Close()
	p := newKiteProducer(t, server)

	histories := p.BootRequest([]entity.MonoCandle{
		{Name: "RELIANCE", Size: entity.MIN1},
		{Name: "RELIANCE", Size: entity.MIN15},
		{Name: "TCS", Size: entity.MIN1},
	})
	if len(histories) != 3 {
		t.Fatalf("histories = %d (calls=%d)", len(histories), *historyCalls)
	}
	if *historyCalls != 3 {
		t.Fatalf("history calls = %d, want 3", *historyCalls)
	}
	for _, history := range histories {
		if len(history.History.Data) != 2 {
			t.Fatalf("%s candles = %d", history.Name, len(history.History.Data))
		}
		candle := history.History.Data[0]
		if candle.Open != 100 || candle.High != 101 || candle.Low != 99 || candle.Close != 100.5 || candle.Volume != 1000 {
			t.Fatalf("candle = %+v", candle)
		}
	}
	if price := p.GetCurrentValue("RELIANCE"); price != 101.5 {
		t.Fatalf("latest = %v", price)
	}
}

func TestZerodhaRollUsesLTP(t *testing.T) {
	server, _ := kiteServer(t)
	defer server.Close()
	p := newKiteProducer(t, server)

	updates := p.RollRequest([]entity.MonoCandle{
		{Name: "RELIANCE", Size: entity.MIN1},
		{Name: "RELIANCE", Size: entity.HOUR1},
	})
	if len(updates) != 2 {
		t.Fatalf("updates = %d", len(updates))
	}
	if p.GetCurrentValue("RELIANCE") != 1333.5 {
		t.Fatalf("price = %v", p.GetCurrentValue("RELIANCE"))
	}
	if updates[0].Candle.Close != 1333.5 {
		t.Fatalf("candle = %+v", updates[0].Candle)
	}
}

func TestZerodhaUnknownInstrument(t *testing.T) {
	server, _ := kiteServer(t)
	defer server.Close()
	p := newKiteProducer(t, server)
	if histories := p.BootRequest([]entity.MonoCandle{{Name: "NOPE", Size: entity.MIN1}}); len(histories) != 0 {
		t.Fatalf("histories = %+v", histories)
	}
}

func TestZerodhaRequiresCredentials(t *testing.T) {
	if _, err := New(Options{APIKey: "key"}); err == nil {
		t.Fatal("expected missing access token error")
	}
	if _, err := New(Options{AccessToken: "token"}); err == nil {
		t.Fatal("expected missing api key error")
	}
}

func TestZerodhaSessionTokenChecksum(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/session/token" || r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		sum := sha256.Sum256([]byte("key" + "request" + "secret"))
		want := hex.EncodeToString(sum[:])
		if got := r.Form.Get("checksum"); got != want {
			t.Errorf("checksum = %q, want %q", got, want)
		}
		fmt.Fprint(w, `{"status":"success","data":{"access_token":"new-token"}}`)
	}))
	defer server.Close()
	p, err := New(Options{BaseURL: server.URL, APIKey: "key", APISecret: "secret", AccessToken: "old"})
	if err != nil {
		t.Fatal(err)
	}
	token, err := p.SessionToken("request")
	if err != nil {
		t.Fatal(err)
	}
	if token != "new-token" {
		t.Fatalf("token = %q", token)
	}
}

func TestParseInstruments(t *testing.T) {
	instruments, err := parseInstruments(strings.NewReader(instrumentsCSV))
	if err != nil {
		t.Fatal(err)
	}
	if len(instruments) != 2 {
		t.Fatalf("instruments = %d", len(instruments))
	}
	instrument, ok := instruments["NSE:RELIANCE"]
	if !ok || instrument.Token != 123 || instrument.Instrument != "EQ" {
		t.Fatalf("instrument = %+v ok=%v", instrument, ok)
	}
}

func TestSymbolNormalizationAndIntervals(t *testing.T) {
	cases := map[string]string{
		"RELIANCE": "RELIANCE",
		"NSE:INFY": "INFY",
		"tcs.ns":   "TCS",
		"  SBIN  ": "SBIN",
	}
	for input, want := range cases {
		if got := normalizeSymbol(input); got != want {
			t.Fatalf("normalizeSymbol(%q) = %q, want %q", input, got, want)
		}
	}
	if intervalFor(entity.MIN1) != "minute" || intervalFor(entity.MIN15) != "15minute" || intervalFor(entity.HOUR1) != "60minute" {
		t.Fatal("unexpected interval mapping")
	}
	if rangeFor("minute") <= 0 || rangeFor("15minute") <= 0 || rangeFor("60minute") <= 0 {
		t.Fatal("ranges must be positive")
	}
}

func TestKiteTimeParsing(t *testing.T) {
	for _, raw := range []string{
		"2026-11-07T09:15:00+0530",
		"2026-11-07T09:15:00+05:30",
		"2026-11-07T03:45:00Z",
		"2026-11-07 09:15:00",
	} {
		if _, err := parseKiteTime(raw); err != nil {
			t.Fatalf("parseKiteTime(%q): %v", raw, err)
		}
	}
	if _, err := parseKiteTime("not-a-time"); err == nil {
		t.Fatal("expected parse error")
	}
}
