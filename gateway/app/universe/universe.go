// Package universe resolves the NSE symbol universe for the gateway.
//
// The primary source is NSE's official index-constituent CSV archive. It is a
// plain CSV download and does not require the session cookie that NSE's JSON
// quote endpoints need. An explicit TYCHE_SYMBOLS override always wins so tests
// and constrained runs can avoid the network.
package universe

import (
	"context"
	"encoding/csv"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	// DefaultIndex is used for real data sources when TYCHE_UNIVERSE is unset.
	DefaultIndex = "nifty500"

	defaultBaseURL = "https://archives.nseindia.com/content/indices"
	// NSE has used both hosts for the same archive. Trying both makes startup
	// resilient to a single host being unavailable.
	alternateBaseURL = "https://nsearchives.nseindia.com/content/indices"
	maxBodyBytes     = 8 << 20
)

var indexFiles = map[string]string{
	"nifty50":             "ind_nifty50list.csv",
	"niftynext50":         "ind_niftynext50list.csv",
	"nifty100":            "ind_nifty100list.csv",
	"nifty200":            "ind_nifty200list.csv",
	"nifty500":            "ind_nifty500list.csv",
	"niftymidcap150":      "ind_niftymidcap150list.csv",
	"niftysmallcap250":    "ind_niftysmallcap250list.csv",
	"niftylargemidcap250": "ind_niftylargemidcap250list.csv",
}

var indexAliases = map[string]string{
	"nifty":               "nifty50",
	"nifty50":             "nifty50",
	"niftynext50":         "niftynext50",
	"niftynext":           "niftynext50",
	"nifty100":            "nifty100",
	"nifty200":            "nifty200",
	"nifty500":            "nifty500",
	"niftymidcap150":      "niftymidcap150",
	"niftymidcap":         "niftymidcap150",
	"niftysmallcap250":    "niftysmallcap250",
	"niftysmallcap":       "niftysmallcap250",
	"niftylargemidcap250": "niftylargemidcap250",
	"niftylargemidcap":    "niftylargemidcap250",
}

// Options controls universe resolution.
type Options struct {
	// Explicit is the TYCHE_SYMBOLS override. When non-empty it wins over Index.
	Explicit []string
	// Index is an NSE index name such as nifty50, nifty100 or nifty500.
	Index string
	// URL is an optional full CSV URL. It takes precedence over Index mapping.
	URL string
	// Limit truncates the fetched universe when greater than zero.
	Limit int
	// Fallback is used when fetching or parsing fails.
	Fallback []string
	// Timeout applies when HTTPClient is nil.
	Timeout time.Duration
	// HTTPClient is injectable for tests.
	HTTPClient *http.Client
}

// Result is the resolved symbol list plus a human-readable source label.
type Result struct {
	Source  string
	Symbols []string
}

// Resolve returns the configured symbol universe. A fetch failure returns the
// fallback list together with the error so callers can log and continue.
func Resolve(ctx context.Context, opts Options) (Result, error) {
	if explicit := normalizeSymbols(opts.Explicit); len(explicit) > 0 {
		return Result{Source: "env", Symbols: explicit}, nil
	}

	index := normalizeIndex(opts.Index)
	if index == "off" || index == "none" || index == "fallback" || index == "env" ||
		(index == "" && strings.TrimSpace(opts.URL) == "") {
		return Result{Source: "fallback", Symbols: normalizeSymbols(opts.Fallback)}, nil
	}

	endpoints, source, err := endpointsFor(index, opts.URL)
	if err != nil {
		return fallbackResult(opts.Fallback), err
	}

	symbols, err := fetch(ctx, endpoints, opts)
	if err != nil {
		return fallbackResult(opts.Fallback), err
	}
	if len(symbols) == 0 {
		return fallbackResult(opts.Fallback), fmt.Errorf("universe %s returned no EQ symbols", source)
	}
	if opts.Limit > 0 && len(symbols) > opts.Limit {
		symbols = symbols[:opts.Limit]
	}
	return Result{Source: source, Symbols: symbols}, nil
}

func endpointsFor(index, customURL string) ([]string, string, error) {
	if strings.TrimSpace(customURL) != "" {
		return []string{strings.TrimSpace(customURL)}, "custom", nil
	}
	file, ok := indexFiles[index]
	if !ok {
		return nil, "", fmt.Errorf("unknown universe %q (supported: %s)", index, strings.Join(SupportedIndices(), ", "))
	}
	return []string{
		strings.TrimRight(defaultBaseURL, "/") + "/" + file,
		strings.TrimRight(alternateBaseURL, "/") + "/" + file,
	}, index, nil
}

// SupportedIndices lists the canonical names accepted by Resolve.
func SupportedIndices() []string {
	return []string{
		"nifty50",
		"niftynext50",
		"nifty100",
		"nifty200",
		"nifty500",
		"niftymidcap150",
		"niftysmallcap250",
		"niftylargemidcap250",
	}
}

func fetch(ctx context.Context, endpoints []string, opts Options) ([]string, error) {
	client := opts.HTTPClient
	if client == nil {
		timeout := opts.Timeout
		if timeout <= 0 {
			timeout = 20 * time.Second
		}
		client = &http.Client{Timeout: timeout}
	}

	var lastErr error
	for _, endpoint := range endpoints {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			lastErr = err
			continue
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Tyche/1.0")
		req.Header.Set("Accept", "text/csv,text/plain;q=0.9,*/*;q=0.8")
		req.Header.Set("Referer", "https://www.nseindia.com/")

		resp, err := client.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		body, readErr := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
		resp.Body.Close()
		if readErr != nil {
			lastErr = readErr
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			lastErr = fmt.Errorf("universe HTTP %d from %s", resp.StatusCode, endpoint)
			continue
		}
		symbols, parseErr := parseCSV(strings.NewReader(string(body)))
		if parseErr != nil {
			lastErr = parseErr
			continue
		}
		return symbols, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no universe endpoint available")
	}
	return nil, lastErr
}

func parseCSV(reader io.Reader) ([]string, error) {
	csvReader := csv.NewReader(reader)
	csvReader.FieldsPerRecord = -1
	rows, err := csvReader.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("parse universe CSV: %w", err)
	}
	if len(rows) < 2 {
		return nil, fmt.Errorf("universe CSV is empty")
	}

	header := make(map[string]int, len(rows[0]))
	for i, column := range rows[0] {
		header[strings.ToLower(strings.TrimSpace(strings.TrimPrefix(column, "\ufeff")))] = i
	}
	column := func(row []string, name string) string {
		index, ok := header[name]
		if !ok || index >= len(row) {
			return ""
		}
		return strings.TrimSpace(row[index])
	}

	symbols := make([]string, 0, len(rows)-1)
	for _, row := range rows[1:] {
		series := column(row, "series")
		if series != "" && !strings.EqualFold(series, "EQ") {
			continue
		}
		symbol := cleanSymbol(column(row, "symbol"))
		if symbol != "" {
			symbols = append(symbols, symbol)
		}
	}
	symbols = normalizeSymbols(symbols)
	if len(symbols) == 0 {
		return nil, fmt.Errorf("universe CSV contained no symbols")
	}
	return symbols, nil
}

func cleanSymbol(raw string) string {
	symbol := strings.TrimSpace(raw)
	if idx := strings.Index(symbol, ":"); idx >= 0 {
		symbol = strings.TrimSpace(symbol[idx+1:])
	}
	return symbol
}

func normalizeSymbols(symbols []string) []string {
	out := make([]string, 0, len(symbols))
	seen := make(map[string]struct{}, len(symbols))
	for _, raw := range symbols {
		symbol := cleanSymbol(raw)
		if symbol == "" {
			continue
		}
		key := strings.ToUpper(symbol)
		if _, duplicate := seen[key]; duplicate {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, symbol)
	}
	return out
}

func normalizeIndex(value string) string {
	normalized := strings.ToLower(strings.TrimSpace(value))
	normalized = strings.NewReplacer(" ", "", "_", "", "-", "").Replace(normalized)
	if canonical, ok := indexAliases[normalized]; ok {
		return canonical
	}
	return normalized
}

func fallbackResult(fallback []string) Result {
	return Result{Source: "fallback", Symbols: normalizeSymbols(fallback)}
}
