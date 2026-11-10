package org.Tyche.src.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.util.Indicators.BollingerResult;
import org.Tyche.src.util.Indicators.DonchianResult;
import org.Tyche.src.util.Indicators.HeikinAshiResult;
import org.Tyche.src.util.Indicators.MacdResult;
import org.Tyche.src.util.Indicators.StochasticResult;
import org.Tyche.src.util.Indicators.SupertrendResult;
import org.junit.jupiter.api.Test;

class IndicatorsTest {

    private static final double EPS = 1e-9;

    private static void assertSeries(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (Double.isNaN(expected[i])) {
                assertTrue(Double.isNaN(actual[i]), "index " + i + " should be NaN but was " + actual[i]);
            } else {
                assertEquals(expected[i], actual[i], EPS, "index " + i);
            }
        }
    }

    @Test
    void extractsCandleFields() {
        List<Candle> candles = List.of(
                new Candle(1, 3, 0.5, 2, 10),
                new Candle(2, 4, 1.5, 3, 20));

        assertArrayEquals(new double[] { 2, 3 }, Indicators.closes(candles), EPS);
        assertArrayEquals(new double[] { 3, 4 }, Indicators.highs(candles), EPS);
        assertArrayEquals(new double[] { 0.5, 1.5 }, Indicators.lows(candles), EPS);
        assertArrayEquals(new double[] { 10, 20 }, Indicators.volumes(candles), EPS);
    }

    @Test
    void smaUsesLastWindowAndWarmsUpWithNaN() {
        double[] values = { 1, 2, 3, 4, 5 };

        assertEquals(4, Indicators.sma(values, 3), EPS);
        assertTrue(Double.isNaN(Indicators.sma(values, 6)));
        assertTrue(Double.isNaN(Indicators.sma(null, 3)));
        assertTrue(Double.isNaN(Indicators.sma(values, 0)));

        assertSeries(new double[] { Double.NaN, Double.NaN, 2, 3, 4 },
                Indicators.smaSeries(values, 3));
        assertSeries(new double[] { Double.NaN, Double.NaN },
                Indicators.smaSeries(new double[] { 1, 2 }, 3));
    }

    @Test
    void emaSeedsWithSma() {
        double[] values = { 1, 2, 3, 4, 5 };

        assertSeries(new double[] { Double.NaN, Double.NaN, 2, 3, 4 },
                Indicators.emaSeries(values, 3));
        assertEquals(4, Indicators.ema(values, 3), EPS);
        assertEquals(0, Indicators.emaSeries(null, 3).length);
    }

    @Test
    void emaSparseSkipsLeadingNaNs() {
        double[] values = { Double.NaN, Double.NaN, 2, 4, 6, 8 };

        double[] out = Indicators.emaSparse(values, 2);

        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertTrue(Double.isNaN(out[2]));
        assertEquals(3, out[3], EPS); // seed = (2 + 4) / 2
        assertEquals(5, out[4], EPS); // alpha = 2/3
        assertEquals(7, out[5], EPS);
    }

    @Test
    void rsiHandlesGainsLossesAndFlatSeries() {
        double[] up = new double[16];
        double[] down = new double[16];
        double[] flat = new double[16];
        for (int i = 0; i < 16; i++) {
            up[i] = 100 + i;
            down[i] = 200 - i;
            flat[i] = 50;
        }

        assertEquals(100, Indicators.rsi(up, 14), EPS);
        assertEquals(0, Indicators.rsi(down, 14), EPS);
        assertEquals(50, Indicators.rsi(flat, 14), EPS);
        assertSeries(new double[] { Double.NaN, Double.NaN, 50 },
                Indicators.rsiSeries(new double[] { 1, 2, 1 }, 2));
        assertEquals(0, Indicators.rsiSeries(null, 14).length);
    }

    @Test
    void macdHistogramMatchesLines() {
        double[] closes = new double[40];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + Math.sin(i / 3.0) * 5;
        }

        MacdResult result = Indicators.macd(closes, 5, 12, 4);

        assertEquals(closes.length, result.macd().length);
        assertEquals(closes.length, result.signal().length);
        assertEquals(closes.length, result.histogram().length);
        assertTrue(Double.isNaN(result.macd()[10]));
        assertFalse(Double.isNaN(result.macd()[11]));
        for (int i = 0; i < closes.length; i++) {
            if (!Double.isNaN(result.histogram()[i])) {
                assertEquals(result.macd()[i] - result.signal()[i], result.histogram()[i], EPS);
            }
        }
    }

    @Test
    void bollingerBandsUsePopulationStdDev() {
        BollingerResult flat = Indicators.bollinger(new double[] { 10, 10, 10, 10, 10 }, 5, 2);
        assertEquals(10, flat.middle(), EPS);
        assertEquals(10, flat.upper(), EPS);
        assertEquals(10, flat.lower(), EPS);

        BollingerResult result = Indicators.bollinger(new double[] { 1, 2, 3, 4, 5 }, 5, 2);
        assertEquals(3, result.middle(), EPS);
        assertEquals(3 + 2 * Math.sqrt(2), result.upper(), EPS);
        assertEquals(3 - 2 * Math.sqrt(2), result.lower(), EPS);

        BollingerResult tooShort = Indicators.bollinger(new double[] { 1, 2 }, 5, 2);
        assertTrue(Double.isNaN(tooShort.middle()));
    }

    @Test
    void trueRangeAndAtrFollowWilder() {
        double[] highs = { 10, 12, 11 };
        double[] lows = { 8, 9, 10 };
        double[] closes = { 9, 11, 10 };

        assertArrayEquals(new double[] { 2, 3, 1 },
                Indicators.trueRangeSeries(highs, lows, closes), EPS);
        assertSeries(new double[] { Double.NaN, 2.5, 1.75 },
                Indicators.atrSeries(highs, lows, closes, 2));
        assertEquals(1.75, Indicators.atr(highs, lows, closes, 2), EPS);
    }

    @Test
    void stochasticAndNaNBehavingSma() {
        double[] highs = { 10, 11, 12, 13 };
        double[] lows = { 8, 9, 10, 11 };
        double[] closes = { 9, 10, 11, 12 };

        StochasticResult result = Indicators.stochastic(highs, lows, closes, 3, 2);

        assertTrue(Double.isNaN(result.k()[0]));
        assertTrue(Double.isNaN(result.k()[1]));
        assertEquals(75, result.k()[2], EPS);
        assertEquals(75, result.k()[3], EPS);
        assertTrue(Double.isNaN(result.d()[2]));
        assertEquals(75, result.d()[3], EPS);

        StochasticResult flat = Indicators.stochastic(
                new double[] { 5, 5, 5 }, new double[] { 5, 5, 5 },
                new double[] { 5, 5, 5 }, 3, 2);
        assertEquals(50, flat.k()[2], EPS);
    }

    @Test
    void cciIsZeroForFlatPrices() {
        double[] highs = { 10, 10, 10, 10 };
        double[] lows = { 10, 10, 10, 10 };
        double[] closes = { 10, 10, 10, 10 };

        assertEquals(0, Indicators.cci(highs, lows, closes, 4), EPS);
        assertTrue(Double.isNaN(Indicators.cci(highs, lows, closes, 5)));
    }

    @Test
    void donchianExcludesCurrentBar() {
        DonchianResult result = Indicators.donchian(
                new double[] { 1, 2, 3, 4 },
                new double[] { 0, 1, 2, 3 }, 2);

        assertEquals(3, result.highest(), EPS);
        assertEquals(1, result.lowest(), EPS);

        DonchianResult tooShort = Indicators.donchian(new double[] { 1, 2 }, new double[] { 0, 1 }, 2);
        assertTrue(Double.isNaN(tooShort.highest()));
    }

    @Test
    void supertrendTracksDirection() {
        int n = 30;
        double[] risingHighs = new double[n];
        double[] risingLows = new double[n];
        double[] risingCloses = new double[n];
        double[] fallingHighs = new double[n];
        double[] fallingLows = new double[n];
        double[] fallingCloses = new double[n];
        for (int i = 0; i < n; i++) {
            risingCloses[i] = 100 + i;
            risingHighs[i] = risingCloses[i] + 1;
            risingLows[i] = risingCloses[i] - 1;

            fallingCloses[i] = 200 - i;
            fallingHighs[i] = fallingCloses[i] + 1;
            fallingLows[i] = fallingCloses[i] - 1;
        }

        SupertrendResult up = Indicators.supertrend(risingHighs, risingLows, risingCloses, 3, 2);
        SupertrendResult down = Indicators.supertrend(fallingHighs, fallingLows, fallingCloses, 3, 2);

        assertTrue(Double.isNaN(up.line()[1]));
        assertTrue(up.bullish(n - 1));
        assertTrue(up.line()[n - 1] < risingCloses[n - 1]);
        assertFalse(down.bullish(n - 1));
        assertTrue(down.line()[n - 1] > fallingCloses[n - 1]);
    }

    @Test
    void obvAccumulatesDirectionalVolume() {
        double[] closes = { 1, 2, 1, 1, 3 };
        double[] volumes = { 10, 20, 30, 40, 50 };

        assertArrayEquals(new double[] { 0, 20, -10, -10, 40 },
                Indicators.obvSeries(closes, volumes), EPS);
    }

    @Test
    void vwapWeightsByVolume() {
        assertEquals(2.5, Indicators.vwap(
                new double[] { 2, 4 },
                new double[] { 0, 2 },
                new double[] { 1, 3 },
                new double[] { 10, 30 }, 2), EPS);
        assertTrue(Double.isNaN(Indicators.vwap(
                new double[] { 2 }, new double[] { 0 }, new double[] { 1 },
                new double[] { 0 }, 1)));
    }

    @Test
    void heikinAshiTransformsCandles() {
        HeikinAshiResult result = Indicators.heikinAshi(
                new double[] { 10, 12 },
                new double[] { 11, 13 },
                new double[] { 9, 11 },
                new double[] { 10, 12 });

        assertArrayEquals(new double[] { 10, 10 }, result.open(), EPS);
        assertArrayEquals(new double[] { 11, 13 }, result.high(), EPS);
        assertArrayEquals(new double[] { 9, 10 }, result.low(), EPS);
        assertArrayEquals(new double[] { 10, 12 }, result.close(), EPS);
    }
}
