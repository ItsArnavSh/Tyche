package org.Tyche.src.core.engine.Scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import org.Tyche.src.core.engine.Scheduler.Functions.*;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class FunctionAlloter {

    HashMap<CandleSize, ArrayList<Consumer<StartParams>>> boot_func;
    HashMap<CandleSize, ArrayList<Consumer<StartParams>>> roll_func;
    HashMap<CandleSize, ArrayList<String>> names;

    public FunctionAlloter() {
        this.boot_func = new HashMap<>();
        this.roll_func = new HashMap<>();
        this.names = new HashMap<>();

        // ==================== 5-Second ====================
        var size = CandleSize.sec5;
        register(size,
                new F_SMA(),
                new F_EMA(),
                new F_RSI2(),
                new F_VolumeBreakout());

        // ==================== 30-Second ====================
        size = CandleSize.sec30;
        register(size,
                new F_SMA(),
                new F_EMA(),
                new F_EMACross(),
                new F_StochRSI(),
                new F_WilliamsR(),
                new F_OBVTrend(),
                new F_RSI50(),
                new F_MFI());

        // ==================== 1-Minute (Main Timeframe) ====================
        size = CandleSize.min1;
        register(size,
                new F_SMA(),
                new F_EMA(),
                new F_DoubleSMA(),
                new F_MACD(),
                new F_RSI(),
                new F_RSI_Divergence(),
                new F_Stochastic(),
                new F_CCI(),
                new F_BollingerBands(),
                new F_ATR(),
                new F_DonchianChannel(),
                new F_Supertrend(),
                new F_VWAP(),
                new F_HeikinAshi(),
                new F_OBV(),
                new F_EMACross(),
                new F_TripleEMA(),
                new F_ADX(),
                new F_Aroon(),
                new F_PSAR(),
                new F_Ichimoku(),
                new F_RSI50(),
                new F_StochRSI(),
                new F_WilliamsR(),
                new F_MFI(),
                new F_AwesomeOscillator(),
                new F_RSI2(),
                new F_KeltnerReversion(),
                new F_ZScoreReversion(),
                new F_PivotBounce(),
                new F_VolumeBreakout(),
                new F_OBVTrend(),
                new F_CMF(),
                new F_ForceIndex(),
                new F_NR7(),
                new F_InsideBar(),
                new F_SqueezeBreakout(),
                new F_BullishEngulfing(),
                new F_Hammer(),
                new F_MorningStar());

        // ==================== 15-Minute ====================
        size = CandleSize.min15;
        register(size,
                new F_SMA(),
                new F_EMA(),
                new F_MACD(),
                new F_Supertrend(),
                new F_BollingerBands(),
                new F_EMACross(),
                new F_TripleEMA(),
                new F_ADX(),
                new F_Aroon(),
                new F_PSAR(),
                new F_Ichimoku(),
                new F_RSI50(),
                new F_WilliamsR(),
                new F_AwesomeOscillator(),
                new F_VolumeBreakout(),
                new F_OBVTrend(),
                new F_CMF(),
                new F_NR7(),
                new F_InsideBar(),
                new F_SqueezeBreakout(),
                new F_BullishEngulfing(),
                new F_Hammer(),
                new F_MorningStar());

        // ==================== 1-Hour ====================
        size = CandleSize.hour1;
        register(size,
                new F_SMA(),
                new F_EMA(),
                new F_DoubleSMA(),
                new F_MACD(),
                new F_Supertrend(),
                new F_EMACross(),
                new F_TripleEMA(),
                new F_ADX(),
                new F_Aroon(),
                new F_PSAR(),
                new F_Ichimoku(),
                new F_RSI50(),
                new F_MFI(),
                new F_OBVTrend(),
                new F_CMF());

        System.out.println(boot_func.values().stream().mapToInt(ArrayList::size).sum()
                + " strategy registrations have been initialized successfully!");
    }

    private void register(CandleSize size, BaseFunction... functions) {
        for (BaseFunction function : functions) {
            boot_func
                    .computeIfAbsent(size, k -> new ArrayList<>())
                    .add(function::Boot);

            roll_func
                    .computeIfAbsent(size, k -> new ArrayList<>())
                    .add(function::Roll);

            names
                    .computeIfAbsent(size, k -> new ArrayList<>())
                    .add(function.name());
        }
    }

    /** Registered strategy names for a timeframe (used by tests and diagnostics). */
    public ArrayList<String> get_names(CandleSize size) {
        return this.names.get(size);
    }

    public ArrayList<Consumer<StartParams>> get_size_funcs(CandleSize size, boolean boot) {
        if (boot) {
            return this.boot_func.get(size);
        } else {
            return this.roll_func.get(size);
        }
    }
}
