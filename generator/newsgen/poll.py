from typing import List, Tuple, Callable, Any
import hashlib
import asyncio
from aiohttp import ClientSession, ClientResponse
import aiohttp
from newspaper import Article

from newsgen.entity import convertToNewsArticle

def hash_content(content: bytes) -> str:
    return hashlib.md5(content).hexdigest()

async def get_data(session: ClientSession, url: str) -> ClientResponse:
    url = url.strip()
    print(f"Fetching: {repr(url)}")
    resp = await session.get(url)
    return resp

async def poll_one_source(name: str, url: str, parser: Callable[[str,str], List[str]]):
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
        while True:
            try:
                response = await get_data(session, url)
                content_bytes = await response.read()
                data_hash = hash_content(content_bytes)

                if hash!=data_hash:
                    hash = data_hash
                    content_str   = content_bytes.decode()
                    news_urls = parser(content_str,top_url)
                    top_url = news_urls[0]
                    for url in news_urls:
                        try:
                            article = await parse_article_async(url)
                            article = convertToNewsArticle(article)
                            print(f"[{name}] {article.title} on {article.timestamp}")
                        except Exception as e:
                            print(f"[{name}] Error: {e.__class__.__name__}: {e}")
            except Exception as e:
                print(f"[{name}] Error: {e.__class__.__name__}: {e}")

            await asyncio.sleep(300)  # wait 5 minutes

async def parse_article_async(url: str):
    def parse_blocking():
        article = Article(url)
        article.download()
        article.parse()
        return article

    return await asyncio.to_thread(parse_blocking)
async def pollNews(sources: List[Tuple[str, str, Callable[[str,str], List[str]]]]):
    tasks = [
        asyncio.create_task(poll_one_source(name, url, parser))
        for name, url, parser in sources
    ]
    await asyncio.gather(*tasks)
