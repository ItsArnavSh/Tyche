package org.Tyche.src.core.engine.Scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;

import org.Tyche.src.core.engine.Scheduler.Functions.F_Demo;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class FunctionAlloter {
    HashMap<CandleSize, ArrayList<Consumer<StartParams>>> boot_func;
    HashMap<CandleSize, ArrayList<Consumer<StartParams>>> roll_func;

    FunctionAlloter() {
        this.boot_func = new HashMap<CandleSize, ArrayList<Consumer<StartParams>>>();
        this.roll_func = new HashMap<CandleSize, ArrayList<Consumer<StartParams>>>();
        // Todo: Insert the functions from here
        var size = CandleSize.sec5;
        F_Demo demo = new F_Demo();
        register(size, demo::Boot, demo::Roll);
        // Todo: Insert the functions from here
        size = CandleSize.sec30;
        demo = new F_Demo();
        register(size, demo::Boot, demo::Roll);
        // Todo: Insert the functions from here
        size = CandleSize.min1;
        demo = new F_Demo();
        register(size, demo::Boot, demo::Roll);
        // Todo: Insert the functions from here
        size = CandleSize.min15;
        demo = new F_Demo();
        register(size, demo::Boot, demo::Roll);
        // Todo: Insert the functions from here
        size = CandleSize.hour1;
        demo = new F_Demo();
        register(size, demo::Boot, demo::Roll);
        System.out.println(boot_func.size() + " strat function(s) have been initialized");
    };

    ArrayList<Consumer<StartParams>> get_size_funcs(CandleSize size, boolean boot) {
        System.out.println("Sending " + size);
        if (boot)
            return this.boot_func.get(size);
        else
            return this.roll_func.get(size);
    }

    private void register(
            CandleSize size,
            Consumer<StartParams> boot,
            Consumer<StartParams> roll) {
        boot_func
                .computeIfAbsent(size, k -> new ArrayList<>())
                .add(boot);

        roll_func
                .computeIfAbsent(size, k -> new ArrayList<>())
                .add(roll);
    }
}
