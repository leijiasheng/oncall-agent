package com.leixs.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DocumentChunk {

    /**
     * 分片内容
     */
    private String content;

    /**
     * 分片在原文档中的起始位置
     */
    private int startIndex;

    /**
     * 分片在原文档中的结束位置
     */
    private int endIndex;

    /**
     * 分片序号（从0开始）
     */
    private int chunkIndex;

    /**
     * 分片标题或上下文信息
     */
    private String title;

    //无参构造函数：json --》 对象（反序列化）//从
    public DocumentChunk() {
    }

    public DocumentChunk(String content, int startIndex, int endIndex, int chunkIndex) {
        this.content = content;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkIndex = chunkIndex;
    }

    @Override //编译时期检查重写函数名
    public String toString() {
        return "DocumentChunk{" +
                "chunkIndex=" + chunkIndex +
                ", title='" + title + '\'' +
                ", contentLength=" + (content != null ? content.length() : 0) +
                ", startIndex=" + startIndex +
                ", endIndex=" + endIndex +
                '}';
    }
}
