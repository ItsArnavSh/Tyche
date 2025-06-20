from newspaper import Article
from dataclasses import dataclass

from proto import pythonservice_pb2


def fetchArticle(url: str) -> pythonservice_pb2.ParseNewsArticleResponse:
    try:
        article = Article(url)
        article.download()
        article.parse()
        article.nlp()  # optional: generates summary, keywords

        return pythonservice_pb2.ParseNewsArticleResponse(
            provider=article.source_url or "Unknown",
            metadata=", ".join(article.keywords)
            if hasattr(article, "keywords")
            else "",
            headline=article.title or "",
            content=article.text or "",
        )

    except Exception as e:
        print(f"[ERROR] Failed to fetch or parse article: {e}")
        return pythonservice_pb2.ParseNewsArticleResponse(
            provider="", metadata="", headline="", content=""
        )
