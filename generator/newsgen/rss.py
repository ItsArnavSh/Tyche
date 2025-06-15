from newsgen.entity import NewsArticle
from rss_parser import RSSParser
import feedparser
from datetime import datetime
import xml.etree.ElementTree as ET
from typing import List
def parse_date_fallback(pub_date: str) -> datetime:
    formats = [
        "%a, %d %b %Y %H:%M:%S %z",  # With +0530
        "%a, %d %b %Y %H:%M:%S %Z",  # With GMT
        "%Y-%m-%dT%H:%M:%S%z",       # ISO 8601
        "%Y-%m-%dT%H:%M:%S",         # ISO 8601 without tz
    ]
    for fmt in formats:
        try:
            return datetime.strptime(pub_date, fmt)
        except ValueError:
            continue
    try:
        # Python 3.7+: native ISO parser (best effort fallback)
        return datetime.fromisoformat(pub_date)
    except Exception:
        raise ValueError(f"Unrecognized date format: {pub_date}")

def general_parser(content: str) -> List[NewsArticle]:
    rss = RSSParser.parse(content)
    articles: List[NewsArticle] = []
    print("len: ",len(rss.channel.items))
    for item in rss.channel.items:
        try:
            url: str = item.links[0].content.strip()
            print(url)
            pub_date: str = item.pub_date.strip()
            print(pub_date)
            timestamp = parse_date_fallback(pub_date)

            articles.append(NewsArticle(
                title="",
                metadata="",
                content="",
                timestamp=timestamp,
                url=url
            ))
        except (IndexError, AttributeError, ValueError):
            continue  # skip malformed items
    return articles

def mint_parser(content: str) -> List[str]:
    parsed = feedparser.parse(content)
    news_urls: List[str] = []

    for entry in parsed.entries:
        link = entry.link
        news_urls.append(str(link))

    return news_urls
