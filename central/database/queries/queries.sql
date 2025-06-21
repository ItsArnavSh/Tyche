
-- name: CleanupOldEntries :exec
BEGIN;

WITH old_lids AS (
    SELECT lid FROM livefeed
    WHERE created_at < now() - interval '10 minutes'
),

word_dfi_updates AS (
    SELECT word_id, COUNT(*) AS cnt
    FROM invertedindex
    WHERE lid IN (SELECT lid FROM old_lids)
    GROUP BY word_id
),

deleted_invertedindex AS (
    DELETE FROM invertedindex
    WHERE lid IN (SELECT lid FROM old_lids)
    RETURNING word_id
),  
deleted_livefeed AS (
    DELETE FROM livefeed
    WHERE lid IN (SELECT lid FROM old_lids)
)

UPDATE dictionary
SET dfi = dfi - word_dfi_updates.cnt
FROM word_dfi_updates
WHERE dictionary.word_id = word_dfi_updates.word_id;

COMMIT;

-- name: UpsertVector :exec
INSERT INTO vecdb (lid, embedding)
VALUES ($1, $2)
ON CONFLICT (lid)
DO UPDATE SET embedding = EXCLUDED.embedding;

-- name: GetRelevantChunks :many
SELECT lid
FROM vecdb
WHERE embedding <-> $1 < $2
ORDER BY embedding <-> $1
LIMIT $3;


-- name: UpsertWordAndIncrementDFI :one
INSERT INTO dictionary (word, dfi)
VALUES ($1, $2)
ON CONFLICT (word)
DO UPDATE SET dfi = dictionary.dfi + $2
RETURNING word_id;


-- name: GetFreqList :many
SELECT * FROM invertedindex
WHERE word_id = $1;


-- name: GetDFI :one
SELECT dfi FROM dictionary WHERE word_id = $1;


-- name: InsertFreq :exec
INSERT INTO invertedindex (word_id, lid, freq)
VALUES ($1, $2, $3)  
ON CONFLICT (word_id, lid)
DO UPDATE SET freq = EXCLUDED.freq;

-- name: InsertWord :one
INSERT INTO dictionary (word, dfi)
VALUES ($1, 0)
ON CONFLICT (word) DO NOTHING
RETURNING word_id;

-- fallback query if INSERT returns nothing:  
-- name: GetWordID :one
SELECT word_id FROM dictionary WHERE word = $1;
--
--id = db.InsertOrGetWord(word)
--if id == nil:
--    id = db.GetWordID(word)



-- name: InsertLivefeed :exec
INSERT INTO livefeed (lid, headline, con_size)
VALUES ($1, $2, $3);

-- name: GetTotalDocs :one
SELECT COUNT(*) FROM livefeed;

-- name: GetAverageDocLength :one
SELECT AVG(con_size)::FLOAT FROM livefeed;

-- name: GetDocSizeByLID :one
SELECT con_size FROM livefeed WHERE lid = $1;

-- name: GetHeadline :one
SELECT headline FROM livefeed WHERE lid = $1;
