import asyncio
from newsgen.poll import pollNews
from newsgen.rss import general_parser,mint_parser
from typing import List,Tuple,Callable
import warnings
from newsgen.entity import NewsArticle

warnings.filterwarnings("ignore", category=SyntaxWarning)
async def main():
    sources: List[Tuple[str, str, Callable[[str], List[NewsArticle]]]] = [
        ("Times Of India ~Markets", "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms", general_parser),
        ("Times Of India ~World", "https://timesofindia.indiatimes.com/rssfeeds/296589292.cms", general_parser),
        ("The Hindu ~World", "https://www.thehindu.com/news/international/feeder/default.rss", general_parser),
        ("LiveMint", "https://www.livemint.com/rss/money", general_parser),
        ("BBC ~World", "https://feeds.bbci.co.uk/news/world/rss.xml", general_parser),
        ("CNBC TV18 ~Economy", "https://www.cnbctv18.com/commonfeeds/v1/cne/rss/economy.xml", general_parser),
        ("Hindu BusinessLine ~Markets", "https://www.thehindubusinessline.com/markets/feeder/default.rss", general_parser),
        ("NDTV ~Profit", "https://feeds.feedburner.com/ndtvprofit-latest", general_parser),
        ("CNBC ~Top News", "https://www.cnbc.com/id/100727362/device/rss/rss.html", general_parser),
        ("CNN ~Top Stories", "http://rss.cnn.com/rss/cnn_topstories.rss", general_parser),
        ("NYTimes ~World", "https://rss.nytimes.com/services/xml/rss/nyt/World.xml", general_parser),
        ("ABC News ~International", "https://abcnews.go.com/abcnews/internationalheadlines", general_parser),
        ("The Hindu ~National", "https://www.thehindu.com/news/national/feeder/default.rss", general_parser),
        ("The Hindu ~Industry", "https://www.thehindu.com/business/Industry/feeder/default.rss", general_parser),
        ("The Hindu ~Economy", "https://www.thehindu.com/business/Economy/feeder/default.rss", general_parser),
    ]
    poller = asyncio.create_task(pollNews(sources))
    await asyncio.gather(poller)



if __name__ == "__main__":
    asyncio.run(main())
