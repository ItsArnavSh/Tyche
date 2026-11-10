package grpc

import (
	"testing"

	"gateway/app/util/entity"
)

func TestCandleConversionPreservesVolume(t *testing.T) {
	history := entity.TickerHistory{
		Name: "RELIANCE",
		History: entity.CandleSeries{
			Size: entity.MIN15,
			Data: []entity.CandleData{{Open: 100, High: 102, Low: 99, Close: 101, Volume: 12345}},
		},
	}
	proto := convertTickerHistoriesToProto([]entity.TickerHistory{history})
	if len(proto) != 1 || len(proto[0].Series) != 1 {
		t.Fatalf("proto = %+v", proto)
	}
	value := proto[0].Series[0].Val[0]
	if value.Volume != 12345 {
		t.Fatalf("volume = %v", value.Volume)
	}
	if value.Open != 100 || value.High != 102 || value.Low != 99 || value.Close != 101 {
		t.Fatalf("value = %+v", value)
	}
}

func TestLatestConversionGroupsByTickerInOrder(t *testing.T) {
	updates := []entity.LatestVal{
		{Stock: entity.MonoCandle{Name: "A", Size: entity.MIN1}, Candle: entity.CandleData{Close: 10, Volume: 100}},
		{Stock: entity.MonoCandle{Name: "B", Size: entity.MIN1}, Candle: entity.CandleData{Close: 20}},
		{Stock: entity.MonoCandle{Name: "A", Size: entity.HOUR1}, Candle: entity.CandleData{Close: 11}},
	}
	proto := convertLatestValsToProto(updates)
	if len(proto) != 2 {
		t.Fatalf("updates = %d", len(proto))
	}
	if proto[0].Name != "A" || proto[1].Name != "B" {
		t.Fatalf("order = %s, %s", proto[0].Name, proto[1].Name)
	}
	if len(proto[0].Vals) != 2 {
		t.Fatalf("A vals = %d", len(proto[0].Vals))
	}
	if proto[0].Vals[0].Val.Volume != 100 {
		t.Fatalf("volume = %v", proto[0].Vals[0].Val.Volume)
	}
}

func TestCandleSizeRoundTrip(t *testing.T) {
	for _, size := range []entity.CandleSize{entity.SEC5, entity.SEC30, entity.MIN1, entity.MIN15, entity.HOUR1} {
		proto := convertCandleSizeToProto(size)
		if got := convertProtoToCandleSize(proto); got != size {
			t.Fatalf("round trip %v -> %v -> %v", size, proto, got)
		}
	}
}

func TestMissingSeriesConversion(t *testing.T) {
	proto := convertTickerHistoriesToProto([]entity.TickerHistory{{
		Name:    "A",
		History: entity.CandleSeries{Size: entity.MIN1},
	}})
	update := convertLatestValsToProto([]entity.LatestVal{{
		Stock:  entity.MonoCandle{Name: "A", Size: entity.MIN1},
		Candle: entity.CandleData{Close: 1},
	}})
	if len(proto) != 1 || len(update) != 1 {
		t.Fatal("conversion produced unexpected shape")
	}
}
