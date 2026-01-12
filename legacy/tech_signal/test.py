import json
import random
import string


def generate_unique_ticker(existing_tickers: set) -> str:
    """Generate a unique 3-5 character uppercase ticker that hasn't been used."""
    while True:
        length = random.randint(3, 5)
        ticker = "".join(random.choices(string.ascii_uppercase, k=length))
        if ticker not in existing_tickers:
            existing_tickers.add(ticker)
            return ticker


def generate_stock_data(num_stocks: int) -> dict:
    """Generate random stock data with unique tickers."""
    existing_tickers = set()
    stocks = []

    for _ in range(num_stocks):
        base_price = round(random.uniform(10.0, 5000.0), 2)
        open_price = round(base_price * random.uniform(0.95, 1.05), 2)
        close_price = round(open_price * random.uniform(0.97, 1.03), 2)
        high_price = round(max(open_price, close_price) * random.uniform(1.00, 1.05), 2)
        low_price = round(min(open_price, close_price) * random.uniform(0.95, 1.00), 2)

        stock = {
            "name": generate_unique_ticker(existing_tickers),
            "open": open_price,
            "close": close_price,
            "high": high_price,
            "low": low_price,
        }
        stocks.append(stock)

    return {"stocks": stocks}


def save_to_json(data: dict, filename: str) -> None:
    """Save the generated data to a JSON file."""
    with open(filename, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)


if __name__ == "__main__":
    stock_data = generate_stock_data(1000)
    save_to_json(stock_data, "random_stocks.json")
    print("Generated 1,000 stock entries and saved to 'random_stocks.json'")
