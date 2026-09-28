package com.leixs.agent.client;

import com.leixs.agent.config.MilvusProperties;
import com.leixs.agent.constant.MilvusConstants;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.*;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class MilvusClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);

    @Autowired
    private MilvusProperties milvusProperties;

    public MilvusServiceClient createClient() {
        MilvusServiceClient client = null;

       try {
           logger.info("正在连接到 Milvus: {}:{}", milvusProperties.getHost(), milvusProperties.getPort());
           client =connectToMilvus();
           logger.info("成功连接到Milvus");

           if (!collectionExists(client, MilvusConstants.MILVUS_COLLECTION_NAME)) {
               logger.info("collection '{}' 不存在，正在创建...", MilvusConstants.MILVUS_COLLECTION_NAME);
               createBizCollection(client);
               logger.info("成功创建 collection '{}'", MilvusConstants.MILVUS_COLLECTION_NAME);

               // 创建索引
               createIndexes(client);
               logger.info("成功创建索引");
           } else {
               logger.info("collection '{}' 已存在", MilvusConstants.MILVUS_COLLECTION_NAME);
           }

           return client;
       } catch (Exception e) {
           logger.error("创建 Milvus 客户端失败", e);
           if (client != null) {
               client.close();
           }
           throw new RuntimeException("创建 Milvus 客户端失败: " + e.getMessage(), e);
       }
    }

    private MilvusServiceClient connectToMilvus() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(milvusProperties.getHost())
                .withPort(milvusProperties.getPort())
                .withConnectTimeout(milvusProperties.getTimeout(), TimeUnit.MILLISECONDS);

        if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
            builder.withAuthorization(milvusProperties.getUsername(), milvusProperties.getPassword());
        }
        return new MilvusServiceClient(builder.build());
    }

    public boolean collectionExists(MilvusServiceClient client, String collectionName) {

        R<Boolean> response =client.hasCollection(HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());
        //0 代表 RPC 调用成功
        if (response.getStatus() != 0) {
            throw new RuntimeException("检查 collection 失败：" + response.getMessage());
        }
        return response.getData();
    }

    private void createBizCollection(MilvusServiceClient client) {
        // 定义字段
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)  // 改为 FloatVector
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        // 创建 collection schema
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false) //关闭动态字段
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .build();

        // 创建 collection
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDescription("Business knowledge collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 collection 创建索引
     */
    private void createIndexes(MilvusServiceClient client) {
        // 为 vector 字段创建索引（FloatVector 使用 IVF_FLAT 和 L2 距离）
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT) 
                .withMetricType(MetricType.L2)  // L2 距离（欧氏距离）
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + response.getMessage());
        }

        logger.info("成功为 vector 字段创建索引");
    }

}
