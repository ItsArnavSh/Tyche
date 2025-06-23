import time
from grpc import aio
import proto.pythonservice_pb2 as pb2
import proto.pythonservice_pb2_grpc as pb2_grpc
from services.backtest import GetDayDataStocks, get_days_between
from services.newspaper import fetchArticle
from sentence_transformers import SentenceTransformer
from services.wordprocessor import generateKeywords


class PythonUtilServicer(pb2_grpc.PythonUtilServicer):
    def __init__(self) -> None:
        self.emb = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")

    async def ParseNewsArticle(self, request, context):
        print(f"Received URL: {request.url}")
        return fetchArticle(request.url)

    async def GenerateEmbeddings(self, request, context):
        return pb2.GenerateEmbeddingsResponse(
            embeddings=self.emb.encode(request.content)
        )

    async def GenerateKeywords(self, request, context):
        text = request.content
        keywords = generateKeywords(text)
        return pb2.GenerateKeywordsResponse(keywords=keywords)

    async def StreamHistorical(self, request:pb2.StreamHistoricalRequest, context):
        days:list[str] = get_days_between(request.startdate,request.enddate)
        # The flow would be to get one days data, trim out the zeros and feed it to the stream, then repeat for all days
        for day in days:
            data = GetDayDataStocks(day,list(request.tickers))
            for row in data:
                yield row
                time.sleep(0.5)
async def serve():
    server = aio.server()
    pb2_grpc.add_PythonUtilServicer_to_server(PythonUtilServicer(), server)
    server.add_insecure_port("[::]:50051")
    await server.start()
    print("Async gRPC server started on port 50051")
    await server.wait_for_termination()
