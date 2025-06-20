
CREATE TABLE IF NOT EXISTS dictionary (
    word_id SERIAL PRIMARY KEY,
    dfi INTEGER NOT NULL,
    word TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS livefeed (
    lid INTEGER PRIMARY KEY,
    created_at TIMESTAMPTZ DEFAULT now(),
    headline TEXT NOT NULL,
    url TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invertedindex (
    word_id INTEGER NOT NULL,
    lid INTEGER NOT NULL,
    freq INTEGER NOT NULL,
    PRIMARY KEY (word_id, lid),
    FOREIGN KEY (word_id) REFERENCES dictionary(word_id),
    FOREIGN KEY (lid) REFERENCES livefeed(lid)
);
CREATE TABLE IF NOT EXISTS vecdb (
    lid INTEGER PRIMARY KEY,
    embedding VECTOR(384),
    FOREIGN KEY (lid) REFERENCES livefeed(lid) ON DELETE CASCADE
);
