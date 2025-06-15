from dataclasses import dataclass
from datetime import datetime
from newspaper import Article

@dataclass
class NewsArticle:
    title: str
    metadata: str
    content: str
    timestamp: datetime
    url:str
def convertToNewsArticle(article: Article) -> NewsArticle:
    publish_date = article.publish_date
    if isinstance(publish_date, str):
          try:
              publish_date = datetime.fromisoformat(publish_date)
          except ValueError:
              publish_date = datetime.utcnow()
    elif publish_date is None:
        publish_date = datetime.utcnow()

    return NewsArticle(
        title=article.title,
        metadata=article.meta_description or "",
        content=article.text,
        timestamp=publish_date,
        url=""
    )
