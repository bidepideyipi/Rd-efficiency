package com.mig.mapsql.mq;

/**
 * IBM MQ 连接与队列配置（来账 / 往账）。
 */
public class MqEndpointConfig {

    private Long id;
    /** 展示名称，如「yl1-支付MQ」 */
    private String name;
    private String host;
    private int port = 1414;
    private String queueManager;
    private String channel;
    /** 来账队列 */
    private String inboundQueue;
    /** 往账队列 */
    private String outboundQueue;
    private String username;
    private String password;
    private String remark;
    private String createdAt;
    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getQueueManager() {
        return queueManager;
    }

    public void setQueueManager(String queueManager) {
        this.queueManager = queueManager;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getInboundQueue() {
        return inboundQueue;
    }

    public void setInboundQueue(String inboundQueue) {
        this.inboundQueue = inboundQueue;
    }

    public String getOutboundQueue() {
        return outboundQueue;
    }

    public void setOutboundQueue(String outboundQueue) {
        this.outboundQueue = outboundQueue;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
