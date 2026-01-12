package org.Tyche.src.core.producer;

import java.util.ArrayList;
import java.util.List;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Blocks.Candle;

public class TargetStocks {
        public static final List<String> stocks = new ArrayList<>(List.of(
                        "BSE", "ITC", "TCS", "INFY", "WIPRO", "HDFCBANK", "ICICIBANK", "SBIN",
                        "RELIANCE", "ADANIENT", "ADANIPORTS", "ONGC", "NTPC", "COALINDIA",
                        "POWERGRID", "MARUTI", "TATAMOTORS", "MAHINDRA", "EICHERMOT", "BAJAJ-AUTO",
                        "BPCL", "HPCL", "HINDUNILVR", "NESTLE", "TITAN", "DMART", "ULTRACEMCO",
                        "JSWSTEEL", "TATASTEEL", "HCLTECH", "TECHM", "PAYTM", "ZOMATO",
                        "NYKAA", "LIC", "IRCTC", "L&T", "SIEMENS", "ABB", "GAIL", "BEL",
                        "HAL", "BHARTIARTL", "VODAFONEIDEA", "YESBANK", "PNB", "BANKBARODA",
                        "MUTHOOT", "BAJAJFINSV", "BAJFINANCE", "HDFCLIFE", "SBI-LIFE",
                        "ASHOKLEY", "ESCORTS", "BHEL", "TATAPOWER", "IDEA", "SUNPHARMA",
                        "DRREDDY", "CIPLA", "APOLLOHOSP", "ZYDUS", "LUPIN", "DIVIS",
                        "ONGC", "IOC", "MRF", "JKTYRE", "TATACHEM", "GODREJCP",
                        "TATAELXSI", "COFORGE", "PERSISTENT", "KPIT", "LTIMINDTREE",
                        "ADANI-GREEN", "ADANI-POWER", "ADANI-TOTAL-GAS", "AMBUJACEM",
                        "ACC", "RAMCOCEM", "SHREECEM", "INDIGO", "SPICEJET",
                        "DELHIVERY", "BLUE-DART", "FEDERALBANK", "CANARABANK",
                        "IDFCFIRSTBANK", "INDUSINDBANK", "JUBLFOOD", "MCDOWELL",
                        "BRITANNIA", "COLPAL", "DABUR", "EMAMILTD"));

        public static final List<CandleSize> sizes = new ArrayList<>(
                        List.of(CandleSize.sec5, CandleSize.sec30, CandleSize.min1, CandleSize.min15,
                                        CandleSize.hour1));

}
