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

    public FunctionAlloter() {
        this.boot_func = new HashMap<>();
        this.roll_func = new HashMap<>();

        // ==================== 5-Second ====================
        var size = CandleSize.sec5;
        register(size, new F_SMA());
        register(size, new F_EMA());

        // ==================== 30-Second ====================
        size = CandleSize.sec30;
        register(size, new F_SMA());
        register(size, new F_EMA());

        // ==================== 1-Minute (Main Timeframe) ====================
        size = CandleSize.min1;

        register(size, new F_SMA());
        register(size, new F_EMA());
        register(size, new F_DoubleSMA());
        register(size, new F_MACD());
        register(size, new F_RSI());
        register(size, new F_RSI_Divergence());
        register(size, new F_Stochastic());
        register(size, new F_CCI());
        register(size, new F_BollingerBands());
        register(size, new F_ATR());
        register(size, new F_DonchianChannel());
        register(size, new F_Supertrend());
        register(size, new F_VWAP());
        register(size, new F_HeikinAshi());
        register(size, new F_OBV());

        // ==================== 15-Minute ====================
        size = CandleSize.min15;
        register(size, new F_SMA());
        register(size, new F_EMA());
        register(size, new F_MACD());
        register(size, new F_Supertrend());
        register(size, new F_BollingerBands());

        // ==================== 1-Hour ====================
        size = CandleSize.hour1;
        register(size, new F_SMA());
        register(size, new F_EMA());
        register(size, new F_DoubleSMA());
        register(size, new F_MACD());
        register(size, new F_Supertrend());

        System.out.println(boot_func.values().stream().mapToInt(ArrayList::size).sum()
                + " strategy functions have been initialized successfully!");
    }

    private void register(CandleSize size, BaseFunction function) {
        boot_func
                .computeIfAbsent(size, k -> new ArrayList<>())
                .add(function::Boot);

        roll_func
                .computeIfAbsent(size, k -> new ArrayList<>())
                .add(function::Roll);
    }

    public ArrayList<Consumer<StartParams>> get_size_funcs(CandleSize size, boolean boot) {
        if (boot) {
            return this.boot_func.get(size);
        } else {
            return this.roll_func.get(size);
        }
    }
}