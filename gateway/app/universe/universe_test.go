package universe

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const sampleCSV = `Company Name,Industry,Symbol,Series,ISIN Code
Alpha Ltd,IT,ALPHA,EQ,INE000A01001
Beta Ltd,IT,BETA,BE,INE000A01002
Gamma Ltd,IT,GAMMA,EQ,INE000A01003
Alpha Duplicate,IT,ALPHA,EQ,INE000A01001
NSE:DELTA,IT,DELTA,EQ,INE000A01004
`

func TestParseCSVFiltersSeriesAndDeduplicates(t *testing.T) {
	symbols, err := parseCSV(strings.NewReader(sampleCSV))
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"ALPHA", "GAMMA", "DELTA"}
	if len(symbols) != len(want) {
		t.Fatalf("symbols = %v, want %v", symbols, want)
	}
	for i := range want {
		if symbols[i] != want[i] {
			t.Fatalf("symbols = %v, want %v", symbols, want)
		}
	}
}

func TestParseCSVWithoutSeriesColumn(t *testing.T) {
	symbols, err := parseCSV(strings.NewReader("Symbol,Company\nA,Alpha\nB,Beta\n"))
	if err != nil {
		t.Fatal(err)
	}
	if len(symbols) != 2 || symbols[0] != "A" || symbols[1] != "B" {
		t.Fatalf("symbols = %v", symbols)
	}
}

func TestResolveExplicitOverride(t *testing.T) {
	result, err := Resolve(context.Background(), Options{
		Explicit: []string{"RELIANCE.NS", "TCS.NS", "reliance.ns"},
		Index:    "nifty500",
		URL:      "http://127.0.0.1:1/never",
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Source != "env" {
		t.Fatalf("source = %q", result.Source)
	}
	if len(result.Symbols) != 2 || result.Symbols[0] != "RELIANCE.NS" || result.Symbols[1] != "TCS.NS" {
		t.Fatalf("symbols = %v", result.Symbols)
	}
}

func TestResolveFetchesAndLimitsCustomURL(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Accept"); !strings.Contains(got, "text/csv") {
			t.Errorf("Accept = %q", got)
		}
		fmt.Fprint(w, sampleCSV)
	}))
	defer server.Close()

	result, err := Resolve(context.Background(), Options{
		Index:      "nifty500",
		URL:        server.URL,
		Limit:      2,
		HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Source != "custom" {
		t.Fatalf("source = %q", result.Source)
	}
	if len(result.Symbols) != 2 || result.Symbols[0] != "ALPHA" || result.Symbols[1] != "GAMMA" {
		t.Fatalf("symbols = %v", result.Symbols)
	}
}

func TestResolveFallsBackOnHTTPError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer server.Close()

	result, err := Resolve(context.Background(), Options{
		Index:      "nifty500",
		URL:        server.URL,
		Fallback:   []string{"FALLBACK.NS"},
		HTTPClient: server.Client(),
		Timeout:    time.Second,
	})
	if err == nil {
		t.Fatal("expected fetch error")
	}
	if result.Source != "fallback" || len(result.Symbols) != 1 || result.Symbols[0] != "FALLBACK.NS" {
		t.Fatalf("result = %+v", result)
	}
}

func TestResolveCustomURLWithoutIndex(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, sampleCSV)
	}))
	defer server.Close()

	result, err := Resolve(context.Background(), Options{
		URL:        server.URL,
		HTTPClient: server.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Source != "custom" || len(result.Symbols) != 3 {
		t.Fatalf("result = %+v", result)
	}
}

func TestResolveUnknownIndexFallsBack(t *testing.T) {
	result, err := Resolve(context.Background(), Options{
		Index:    "banana",
		Fallback: []string{"FALLBACK.NS"},
	})
	if err == nil {
		t.Fatal("expected unknown-index error")
	}
	if result.Source != "fallback" || len(result.Symbols) != 1 {
		t.Fatalf("result = %+v", result)
	}
}

func TestResolveOffUsesFallback(t *testing.T) {
	result, err := Resolve(context.Background(), Options{
		Index:    "off",
		Fallback: []string{"A", "B"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Source != "fallback" || len(result.Symbols) != 2 {
		t.Fatalf("result = %+v", result)
	}
}

func TestNormalizeIndex(t *testing.T) {
	cases := map[string]string{
		"NIFTY 500":          "nifty500",
		"nifty-50":           "nifty50",
		"nifty_next_50":      "niftynext50",
		"NIFTYMIDCAP150":     "niftymidcap150",
		"NIFTY SMALLCAP 250": "niftysmallcap250",
		"banana":             "banana",
		"":                   "",
	}
	for input, want := range cases {
		if got := normalizeIndex(input); got != want {
			t.Fatalf("normalizeIndex(%q) = %q, want %q", input, got, want)
		}
	}
}
