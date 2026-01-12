package newsparser

import (
	"central/application/util/entity"
	database "central/database/gen"
	"context"
	"math"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/pgvector/pgvector-go"
	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func (n *NewsStruct) IsMajorNews(ctx context.Context, news entity.NewsChanEntry) (bool, error) {
	// Upserting content

	relevant, err := n.upsertNews(ctx, news.Title)
	if err != nil {
		n.logger.Info("Unable to upsert", zap.Error(err))
		return false, err
	}
	return relevant, nil
}

func (n *NewsStruct) PurgeNews(ctx context.Context) {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()
	for {
		err := n.db.CleanupOldEntries(ctx)
		if err != nil {
			n.logger.Error("Unable to purge Entries", zap.Error(err))
		}
		<-ticker.C
	}
}
func (n *NewsStruct) upsertNews(ctx context.Context, headline string) (bool, error) {
	relevant := false
	lid := int32(uuid.New().ID())
	err := n.db.InsertLivefeed(ctx, database.InsertLivefeedParams{Lid: lid, Headline: headline})
	if err != nil {
		n.logger.Error("Unable to save news on db", zap.Error(err))
	}
	//First Upsert to vectorDB

	resp, err := n.pyclient.CallGenerateEmbeddings(ctx, headline)
	if err != nil {
		return false, err
	}
	embeddings := resp.Embeddings
	semanticrankings, err := n.semanticRankings(ctx, embeddings)
	if err != nil {
		n.logger.Error("Could not fetch embeddings", zap.Error(err))
		return false, err
	}
	err = n.db.UpsertVector(ctx, database.UpsertVectorParams{Lid: lid,
		Embedding: pgvector.NewVector(embeddings)})
	if err != nil {
		n.logger.Error("Error Upserting Vector", zap.Error(err))
	}
	// Break into keywords
	keywords, err := n.pyclient.CallGenerateKeywords(ctx, headline)
	if err != nil {
		n.logger.Error("Error generating keywords", zap.Error(err))
		return false, err
	}
	bmrankings, err := n.bm25(ctx, keywords)
	if err != nil {
		return false, err
	}
	mergedRes := n.PerformIntersection(bmrankings, semanticrankings)
	if len(mergedRes) > viper.GetInt("search.sim_thres") {
		relevant = true
	}
	keyfreq := n.WordFrequencies(keywords)
	//Now upsert the keywords into the dictionary
	for word, count := range keyfreq {
		id, err := n.db.UpsertWordAndIncrementDFI(ctx, database.UpsertWordAndIncrementDFIParams{Word: word, Dfi: count})
		if err != nil {
			n.logger.Error("Error inserting words in dictionary")
			return false, err
		}
		//Upserting into the inverted index
		err = n.db.InsertFreq(ctx, database.InsertFreqParams{WordID: id, Freq: count, Lid: lid})
		if err != nil {
			n.logger.Error("Error inserting into inverted index", zap.Error(err))
			return false, err
		}
	}
	return relevant, nil
}
func (n *NewsStruct) WordFrequencies(words []string) map[string]int32 {
	freqMap := make(map[string]int32)
	for _, word := range words {
		freqMap[word]++
	}
	return freqMap
}
func (n *NewsStruct) semanticRankings(ctx context.Context, embeddings []float32) ([]int32, error) {
	relevance := float32(viper.GetFloat64("search.semantic.relevance"))
	vector := pgvector.NewVector(embeddings)
	return n.db.GetRelevantChunks(ctx, database.GetRelevantChunksParams{Embedding: vector, Embedding_2: relevance, Limit: 5})

}
func (n *NewsStruct) bm25(ctx context.Context, keywords []string) ([]int32, error) {
	B := float32(viper.GetFloat64("search.bm25.B"))
	K := float32(viper.GetFloat64("search.bm25.K"))
	bmmap := make(map[int32]float32)
	avgdl, err := n.db.GetAverageDocLength(ctx)
	if err != nil {
		return nil, err
	}
	totalRepo, err := n.db.GetTotalDocs(ctx)
	if err != nil {
		return nil, err
	}
	for _, keyword := range keywords {
		wordid, err := n.db.UpsertWordAndIncrementDFI(ctx, database.UpsertWordAndIncrementDFIParams{Word: keyword, Dfi: 0})
		if err != nil {
			n.logger.Error("Unable to fetch word id while bm25", zap.Error(err))
			return nil, err
		}
		dfi, err := n.db.GetDFI(ctx, wordid)
		if err != nil {
			n.logger.Error("Unable to get dfi", zap.Error(err))
			return nil, err
		}
		freq_list, err := n.db.GetFreqList(ctx, wordid)
		if err != nil {
			n.logger.Error("Unable to get freq list", zap.Error(err))
			return nil, err
		}

		idf := math.Log(float64((float32(totalRepo)-float32(dfi)+0.5)/(float32(dfi)+float32(0.5)) + 1))
		for _, freq := range freq_list {
			docsize, err := n.db.GetDocSizeByLID(ctx, freq.Lid)

			if err != nil {
				return nil, err
			}

			currbm := (float32(freq.Freq) * (K + 1)) /
				(float32(freq.Freq) + K*(1-B+B*float32(docsize)/float32(avgdl))) *
				float32(idf)
			bmmap[freq.Lid] += currbm
		}
	}
	sortedKeys := n.SortBM25KeysByValueDesc(bmmap)
	return sortedKeys, nil
}

func (n *NewsStruct) SortBM25KeysByValueDesc(bm25 map[int32]float32) []int32 {
	type kv struct {
		Key   int32
		Value float32
	}

	// Convert map to slice of kv pairs
	var sorted []kv
	for k, v := range bm25 {
		sorted = append(sorted, kv{k, v})
	}

	// Sort by Value descending
	sort.Slice(sorted, func(i, j int) bool {
		return sorted[i].Value > sorted[j].Value
	})

	// Extract sorted keys
	var sortedKeys []int32
	for _, pair := range sorted {
		sortedKeys = append(sortedKeys, pair.Key)
	}

	return sortedKeys
}
func (n *NewsStruct) PerformIntersection(a, b []int32) []int32 {
	m := make(map[int32]bool)
	for _, v := range a {

		m[v] = true
	}

	var result []int32
	for _, v := range b {
		if m[v] {
			result = append(result, v)
		}
	}
	return result
}
