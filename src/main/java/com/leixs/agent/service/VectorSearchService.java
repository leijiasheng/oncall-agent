package com.leixs.agent.service;

import com.leixs.agent.constant.MilvusConstants;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            SearchParam searchParam = SearchParam.newBuilder()
                    // 指定要搜索的Milvus集合名称，常量配置
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    // 指定集合里面向量字段的名字，建表时定义为vector
                    .withVectorFieldName("vector")
                    // withVectors接收向量集合；Milvus支持批量搜索，这里只搜1条query，所以包装成单元素list
                    .withVectors(Collections.singletonList(queryVector))
                    // topK：每个查询返回相似度最高的topK条数据
                    .withTopK(topK)
                    // 距离度量类型 L2欧氏距离；L2值越小代表向量越相似
                    .withMetricType(MetricType.L2)
                    // withOutFields 指定搜索时要返回哪些附加字段，不写只会返回id和分数；这里取出id、原文content、元数据metadata
                    .withOutFields(List.of("id", "content", "metadata"))
                    // 检索参数，IVF索引的nprobe，代表检索多少个聚类；nprobe越大召回越高，速度越慢
                    .withParams("{\"nprobe\":10}")
                    .build(); // 构建完成SearchParam对象

            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());

                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }
                results.add(result);
            }

            logger.info("搜索完成, 找到 {} 个相似文档", results.size());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    @Getter
    @Setter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
    }
}
