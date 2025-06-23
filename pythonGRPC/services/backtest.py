import proto.pythonservice_pb2 as pb2
import yfinance as yf
import pandas as pd
from datetime import datetime, timedelta

def GetDayDataStocks(start: str, tickers: list[str]) -> list[pb2.StreamHistoricalResponse]:
    # Download 1-minute data for given tickers
    df = yf.download(tickers=tickers, start=start, period="1d", interval="5m", group_by='ticker')

    responses = []

    # Remove all rows where any value is NaN
    if len(tickers) == 1:
        # Single ticker: no column level grouping
        df = df.dropna()
        for timestamp, row in df.iterrows():
            stock = pb2.StockData(
                ticker=tickers[0],
                open=row["Open"],
                close=row["Close"],
                low=row["Low"],
                high=row["High"],
                volume=row["Volume"]
            )
            response = pb2.StreamHistoricalResponse(
                timestamp=str(timestamp),
                stocks=[stock]
            )
            responses.append(response)
    else:
        # Multiple tickers: MultiIndex columns like ('AAPL', 'Open')
        df = df.dropna()

        for timestamp, row in df.iterrows():
            stock_list = []
            for ticker in tickers:
                stock = pb2.StockData(
                    ticker=ticker,
                    open=row[(ticker, "Open")],
                    close=row[(ticker, "Close")],
                    low=row[(ticker, "Low")],
                    high=row[(ticker, "High")],
                    volume=row[(ticker, "Volume")],
                )
                stock_list.append(stock)

            response = pb2.StreamHistoricalResponse(
                timestamp=str(timestamp),
                stocks=stock_list
            )
            responses.append(response)

    return responses

def get_days_between(start_date: str, end_date: str):
    """
    Returns a list of dates from start_date to end_date (excluding end_date),
    all in 'yyyy-mm-dd' format.
    """
    start = datetime.strptime(start_date, "%Y-%m-%d")
    end = datetime.strptime(end_date, "%Y-%m-%d")

    days = []
    current = start
    while current < end:
        days.append(current.strftime("%Y-%m-%d"))
        current += timedelta(days=1)

    return days
