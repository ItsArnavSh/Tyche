package newsparser

import (
	"central/application/util/entity"
	database "central/database/gen"
	"context"
	"math"
	"sort"
	"time"

	"github.com/google/uuid"
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
func Float32ToFloat64(input []float32) []float64 {
	output := make([]float64, len(input))
	for i, v := range input {
		output[i] = float64(v)
	}
	return output
}
func (n *NewsStruct) upsertNews(ctx context.Context, headline string) (bool, error) {
	relevant := false
	lid := int32(uuid.New().ID())
	//First Upsert to vectorDB

	resp, err := n.pyclient.CallGenerateEmbeddings(ctx, headline)
	embeddings := resp.Embeddings
	semanticrankings, err := n.semanticRankings(ctx, Float32ToFloat64(embeddings))
	if err != nil {
		n.logger.Error("Could not fetch embeddings", zap.Error(err))
		return false, err
	}
	n.db.UpsertVector(ctx, database.UpsertVectorParams{Lid: lid,
		Embedding: embeddings})

	// Break into keywords
	keywords, err := n.pyclient.CallGenerateKeywords(ctx, headline)
	if err != nil {
		n.logger.Error("Error generating keywords", zap.Error(err))
		return false, err
	}
	bmrankings, err := n.bm25(ctx, keywords)
	mergedRes := n.PerformIntersection(bmrankings, semanticrankings)
	if len(mergedRes) > viper.GetInt("newstreshold") {
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
func (n *NewsStruct) semanticRankings(ctx context.Context, embeddings []float64) ([]int32, error) {
	relevance := viper.GetFloat64("semantic.relevance")

	return n.db.GetRelevantChunks(ctx, database.GetRelevantChunksParams{Embedding: embeddings, Embedding_2: relevance, Limit: 5})

}
func (n *NewsStruct) bm25(ctx context.Context, keywords []string) ([]int32, error) {
	B := viper.GetFloat64("bm25.B")
	K := viper.GetFloat64("bm25.K")
	bmmap := make(map[int32]float64)
	avgdl := 15.0   //Todo: Get values for DB for average doc size
	totalRepo := 10 //Todo: Get values from the DB
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

		idf := math.Log((float64(totalRepo)-float64(dfi)+0.5)/(float64(dfi)+float64(0.5)) + 1)
		for _, freq := range freq_list {
			docsize := 10 //Todo: Get these values also from db

			currbm := (float64(freq.Freq) * (K + 1)) / (float64(freq.Freq) + K*(1-B+B*float64(docsize)/avgdl)) * idf
			bmmap[freq.Lid] += currbm
		}
	}
	sortedKeys := n.SortBM25KeysByValueDesc(bmmap)
	return sortedKeys, nil
}

func (n *NewsStruct) SortBM25KeysByValueDesc(bm25 map[int32]float64) []int32 {
	type kv struct {
		Key   int32
		Value float64
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
