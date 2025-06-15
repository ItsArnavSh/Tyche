from typing import List, Tuple, Callable, Any
import hashlib
import asyncio
from aiohttp import ClientSession, ClientResponse
import aiohttp
from newspaper import Article

from newsgen.entity import NewsArticle, convertToNewsArticle

def hash_content(content: bytes) -> str:
    return hashlib.md5(content).hexdigest()

async def get_data(session: ClientSession, url: str) -> ClientResponse:
    url = url.strip()
    print(f"Fetching: {repr(url)}")
    resp = await session.get(url)
    return resp

async def poll_one_source(name: str, srcurl: str, parser: Callable[[str], List[NewsArticle]]):
    async with aiohttp.ClientSession(headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                      (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
        "Referer": "https://www.livemint.com/",
        "Connection": "keep-alive"
    }
) as session:
        top_url:str = ""
        hash  = ""
        content_str=""
        while True:
            try:
                response = await get_data(session, srcurl)
                content_bytes = await response.read()
                data_hash = hash_content(content_bytes)
                print(f"Hash: {hash} and data_hash = {data_hash}")
                if hash!=data_hash:

                    hash = data_hash
                    content_str   = content_bytes.decode()

                    news = parser(content_str)
                    news_urls = update_urls(news,top_url)
                    top_url = news_urls[0].url
                    news_urls = news_urls[::-1]

                    for news in news_urls:
                        try:
                            article = parse_article(news.url)
                            article = convertToNewsArticle(article)
                            article.timestamp = news.timestamp
                            article.url = news.url
                            print(f"[{name}] {article.title} on {article.timestamp}")
                            # print(f"Url: {url}")
                        except Exception as e:
                            print(f"[{name}] url Error: {e.__class__.__name__}: {e} ")
                else:
                    print(f"No New news on {name}")
            except Exception as e:
                print(f"[{name}] Error: {e.__class__.__name__}: {e}")

            await asyncio.sleep(5)

def parse_article(url: str):
    article = Article(url)
    article.download()
    article.parse()
    return article

async def pollNews(sources: List[Tuple[str, str, Callable[[str], List[NewsArticle]]]]):
    tasks = [
        asyncio.create_task(poll_one_source(name, url, parser))
        for name, url, parser in sources
    ]
    await asyncio.gather(*tasks)


def update_urls(lst: List[NewsArticle], target: str) -> List[NewsArticle]:
    for i, article in enumerate(lst):
        if article.url == target:
            return lst[:i]
    return lst
