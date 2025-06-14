import asyncio
from newsgen.poll import pollNews
from newsgen.rss import general_parser,mint_parser
from typing import List,Tuple,Callable
import warnings

warnings.filterwarnings("ignore", category=SyntaxWarning)
async def main():
    sources:List[Tuple[str, str, Callable[[str,str], List[str]]]]= [
      ("Times Of India","https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",general_parser),
      ("LiveMint","https://www.livemint.com/rss/money",mint_parser),
      ("CTV18","https://www.cnbctv18.com/commonfeeds/v1/cne/rss/economy.xml",general_parser),
      ("HinduBusinessLine","https://www.thehindubusinessline.com/markets/feeder/default.rss",general_parser),
      ("NDTV","https://feeds.feedburner.com/ndtvprofit-latest",general_parser),
    ]
    poller = asyncio.create_task(pollNews(sources))
    await asyncio.gather(poller)



if __name__ == "__main__":
    asyncio.run(main())
