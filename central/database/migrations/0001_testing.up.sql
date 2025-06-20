
CREATE TABLE dictionary (
    word_id SERIAL PRIMARY KEY,
    word TEXT NOT NULL UNIQUE
);

CREATE TABLE invertedindex (
    word_id INTEGER PRIMARY KEY,
    dfi INTEGER NOT NULL,
    FOREIGN KEY (word_id) REFERENCES dictionary(word_id)
);

CREATE TABLE freqtable (
    word_id INTEGER NOT NULL,
    repo_id INTEGER NOT NULL,
    freq INTEGER NOT NULL,
    PRIMARY KEY (word_id, repo_id),
    FOREIGN KEY (word_id) REFERENCES dictionary(word_id)
);
