from text_prettifier import TextPrettifier
from nltk.stem import PorterStemmer
from nltk.tokenize import word_tokenize
from typing import List

ps = PorterStemmer()
prettifier = TextPrettifier()


def generateKeywords(content: str) -> List[str]:
    try:
        content = content.lower()
        content = prettifier.remove_special_chars(content)
        content = prettifier.remove_stopwords(content)

        words = content.split()
        final_list: List[str] = []

        for word in words:
            if word.strip():  # skip empty strings
                try:
                    stemmed = ps.stem(word)
                    final_list.append(stemmed)
                except Exception as e:
                    print(f"Error stemming word '{word}': {e}")
                    continue

        return final_list

    except Exception as e:
        print(f"Keyword generation failed: {e}")
        return []

