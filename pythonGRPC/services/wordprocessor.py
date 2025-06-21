from text_prettifier import TextPrettifier
from nltk.stem import PorterStemmer
from nltk.tokenize import word_tokenize
from typing import List

ps = PorterStemmer
prettifier = TextPrettifier()


def generateKeywords(content: str) -> List[str]:
    content = content.lower()

    content = prettifier.remove_special_chars(content)
    content = prettifier.remove_stopwords(content)

    words = content.split()
    finalList: List[str] = []
    for word in words:
        finalList.append(ps.stem(word))
    return finalList
