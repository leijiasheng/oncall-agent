package com.leixs.agent.controller;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.ShowCollectionsParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/milvus")
public class MilvusCheckController {

    @Autowired
    private MilvusServiceClient milvusClient;

    /**
     * 简单的健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> simpleHealth() {
        Map<String, Object> result = new HashMap<>();

        try {
            R<ShowCollectionsResponse> response = milvusClient.showCollections(
                    ShowCollectionsParam.newBuilder().build()
            );

            if (response.getStatus() == 0) {
                result.put("message", "ok");
                result.put("collections", response.getData().getCollectionNamesList());
                return ResponseEntity.ok(result);
            } else {
                result.put("message", "Milvus 服务不可用");
                return ResponseEntity.status(503).body(result);
            }
        } catch (Exception e) {
            result.put("error", "Milvus 服务不可用");
            return ResponseEntity.status(503).body(result);
        }
    }
}
