from rss_parser import RSSParser
import feedparser
import xml.etree.ElementTree as ET
from typing import List
def general_parser(content:str,top_url:str)->List[str]:
    rss = RSSParser.parse(content)
    news_urls:List[str] = []
    for item in rss.channel.items:
        link:str = item.links[0].content
        if link==top_url:
            return news_urls
        news_urls.append(link)
    return news_urls

def mint_parser(content: str, top_url: str) -> List[str]:
    parsed = feedparser.parse(content)
    news_urls: List[str] = []

    for entry in parsed.entries:
        link = entry.link
        if link == top_url:
            break
        news_urls.append(str(link))

    return news_urls
