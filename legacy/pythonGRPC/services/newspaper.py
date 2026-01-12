from newspaper import Article, article
from dataclasses import dataclass

from proto import pythonservice_pb2


def fetchArticle(url: str) -> pythonservice_pb2.ParseNewsArticleResponse:
    try:
        page = Article(url)
        page.download()
        page.parse()

        return pythonservice_pb2.ParseNewsArticleResponse(
            provider="",
            metadata=page.meta_description,
            headline=page.title,
            content=page.text,
        )

    except Exception as e:
        print(f"[ERROR] Failed to fetch or parse article: {e}")
        return pythonservice_pb2.ParseNewsArticleResponse(
            provider="", metadata="", headline="", content=""
        )
