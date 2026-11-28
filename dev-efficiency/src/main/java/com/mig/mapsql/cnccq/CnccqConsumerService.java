package com.mig.mapsql.cnccq;

import cn.cfit.cnccq.jms.ByteSequence;
import cn.cfit.cnccq.jms.CnccqDestination;
import cn.cfit.cnccq.jms.CnccqJmsConsumer;
import cn.cfit.cnccq.jms.CnccqJmsContext;
import cn.cfit.cnccq.jms.CnccqMessage;
import cn.cfit.cnccq.pool.CnccqJmsConnectionPooledFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台线程轮询 CNCCQ receive；支持来账 / 往账 / 两者；消息写入环形缓冲供页面轮询。
 */
@Service
public class CnccqConsumerService {

    private static final Logger log = LoggerFactory.getLogger(CnccqConsumerService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RECENT = 200;
    private static final int BODY_PREVIEW_MAX = 4000;
    private static final int RECEIVE_WAIT_MS = 800;

    public enum QueueSide {
        INBOUND, OUTBOUND, BOTH;

        static QueueSide parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return BOTH;
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            if ("IN".equals(v) || "INBOUND".equals(v) || "来账".equals(raw.trim())) {
                return INBOUND;
            }
            if ("OUT".equals(v) || "OUTBOUND".equals(v) || "往账".equals(raw.trim())) {
                return OUTBOUND;
            }
            return BOTH;
        }
    }

    private final CnccqConfigRepository repository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong consumedTotal = new AtomicLong(0);
    private final ConcurrentLinkedDeque<Map<String, Object>> recent = new ConcurrentLinkedDeque<Map<String, Object>>();

    private volatile Long activeConfigId;
    private volatile String activeConfigName;
    private volatile QueueSide activeSide = QueueSide.BOTH;
    private volatile String lastError;
    private volatile String startedAt;
    private volatile Thread worker;

    public CnccqConsumerService(CnccqConfigRepository repository) {
        this.repository = repository;
    }

    public synchronized Map<String, Object> start(long configId, String sideRaw) {
        if (running.get()) {
            throw new IllegalArgumentException("已在消费中，请先停止（配置 id=" + activeConfigId + "）");
        }
        CnccqEndpointConfig cfg = repository.findById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("CNCCQ 配置不存在: id=" + configId);
        }
        QueueSide side = QueueSide.parse(sideRaw);
        List<String> queues = resolveQueues(cfg, side);
        if (queues.isEmpty()) {
            throw new IllegalArgumentException("所选侧无可用队列名，请检查配置");
        }

        recent.clear();
        consumedTotal.set(0);
        lastError = null;
        activeConfigId = cfg.getId();
        activeConfigName = cfg.getName();
        activeSide = side;
        startedAt = LocalDateTime.now().format(TS);
        running.set(true);

        final CnccqEndpointConfig snapshot = cfg;
        final List<String> queueNames = queues;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                consumeLoop(snapshot, queueNames);
            }
        }, "cnccq-consumer-" + configId);
        t.setDaemon(true);
        worker = t;
        t.start();

        Map<String, Object> body = status();
        body.put("ok", true);
        body.put("message", "已开始消费");
        return body;
    }

    public synchronized Map<String, Object> stop() {
        if (!running.get()) {
            Map<String, Object> body = status();
            body.put("ok", true);
            body.put("message", "当前未在消费");
            return body;
        }
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        Map<String, Object> body = status();
        body.put("ok", true);
        body.put("message", "已停止消费");
        return body;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("running", running.get());
        m.put("configId", activeConfigId);
        m.put("configName", activeConfigName);
        m.put("side", activeSide == null ? null : activeSide.name());
        m.put("startedAt", startedAt);
        m.put("consumedTotal", consumedTotal.get());
        m.put("lastError", lastError);
        m.put("recent", new ArrayList<Map<String, Object>>(recent));
        return m;
    }

    public Map<String, Object> testConnect(long configId) {
        CnccqEndpointConfig cfg = repository.findById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("CNCCQ 配置不存在: id=" + configId);
        }
        CnccqJmsConnectionPooledFactory factory = null;
        CnccqJmsContext context = null;
        try {
            factory = openFactory(cfg);
            context = factory.createContext();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("message", "连接成功");
            m.put("host", cfg.getHost());
            m.put("port", cfg.getPort());
            m.put("queueManager", cfg.getQueueManager());
            return m;
        } catch (Exception e) {
            throw new IllegalArgumentException("连接失败: " + rootMessage(e), e);
        } finally {
            closeQuietly(context);
        }
    }

    /** 查询队列消息数量（积压），不消费。side=INBOUND|OUTBOUND|BOTH */
    public Map<String, Object> inquireDepth(long configId, String sideRaw) {
        CnccqEndpointConfig cfg = repository.findById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("CNCCQ 配置不存在: id=" + configId);
        }
        QueueSide side = QueueSide.parse(sideRaw);
        List<String> queues = resolveQueues(cfg, side);
        if (queues.isEmpty()) {
            throw new IllegalArgumentException("所选侧无可用队列名，请检查配置");
        }

        CnccqJmsConnectionPooledFactory factory;
        CnccqJmsContext context = null;
        CnccqJmsConsumer consumer = null;
        List<Map<String, Object>> depths = new ArrayList<Map<String, Object>>();
        try {
            factory = openFactory(cfg);
            context = factory.createContext();
            String firstQ = queues.get(0);
            CnccqDestination dest = new CnccqDestination(firstQ, cfg.getQueueManager().trim());
            consumer = (CnccqJmsConsumer) context.createConsumer(dest, true);
            for (String qName : queues) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("queue", qName);
                try {
                    int count = consumer.getMessageCount(qName);
                    row.put("ok", true);
                    row.put("currentDepth", count);
                } catch (Exception e) {
                    row.put("ok", false);
                    row.put("currentDepth", null);
                    row.put("error", rootMessage(e));
                }
                depths.add(row);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("查询积压失败: " + rootMessage(e), e);
        } finally {
            closeQuietly(consumer);
            closeQuietly(context);
        }

        int total = 0;
        for (Map<String, Object> d : depths) {
            Object cur = d.get("currentDepth");
            if (cur instanceof Number) {
                total += ((Number) cur).intValue();
            }
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("configId", cfg.getId());
        m.put("configName", cfg.getName());
        m.put("queueManager", cfg.getQueueManager());
        m.put("side", side.name());
        m.put("totalDepth", total);
        m.put("queues", depths);
        m.put("inquiredAt", LocalDateTime.now().format(TS));
        return m;
    }

    @PreDestroy
    public void shutdown() {
        try {
            stop();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void consumeLoop(CnccqEndpointConfig cfg, List<String> queueNames) {
        CnccqJmsContext context = null;
        List<CnccqJmsConsumer> consumers = new ArrayList<CnccqJmsConsumer>();
        try {
            CnccqJmsConnectionPooledFactory factory = openFactory(cfg);
            context = factory.createContext();
            String qm = cfg.getQueueManager().trim();
            for (String q : queueNames) {
                CnccqDestination dest = new CnccqDestination(q, qm);
                consumers.add((CnccqJmsConsumer) context.createConsumer(dest, true));
            }
            appendEvent("INFO", null, "已连接并开始消费: " + String.join(", ", queueNames));

            while (running.get()) {
                boolean got = false;
                for (int i = 0; i < consumers.size(); i++) {
                    if (!running.get()) {
                        break;
                    }
                    CnccqJmsConsumer consumer = consumers.get(i);
                    String qName = queueNames.get(i);
                    try {
                        CnccqDestination dest = new CnccqDestination(qName, qm);
                        CnccqMessage msg = consumer.receive(dest, true, RECEIVE_WAIT_MS);
                        if (msg != null) {
                            got = true;
                            handleMessage(qName, msg);
                        }
                    } catch (Exception e) {
                        if (!running.get()) {
                            break;
                        }
                        lastError = rootMessage(e);
                        log.warn("CNCCQ 收取失败: {}", lastError);
                        appendEvent("ERROR", qName, lastError);
                        sleepQuiet(1000L);
                    }
                }
                if (!got && running.get()) {
                    sleepQuiet(50L);
                }
            }
            appendEvent("INFO", null, "消费已停止");
        } catch (Exception e) {
            lastError = rootMessage(e);
            log.error("CNCCQ 消费异常", e);
            appendEvent("ERROR", null, lastError);
        } finally {
            for (CnccqJmsConsumer c : consumers) {
                closeQuietly(c);
            }
            closeQuietly(context);
            running.set(false);
        }
    }

    private void handleMessage(String queueName, CnccqMessage msg) {
        long n = consumedTotal.incrementAndGet();
        String body = extractBody(msg);
        String msgId = null;
        try {
            msgId = msg.getJMSMessageID();
        } catch (Exception ignored) {
            // ignore
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("seq", n);
        row.put("time", LocalDateTime.now().format(TS));
        row.put("level", "MSG");
        row.put("queue", queueName);
        row.put("messageId", msgId);
        row.put("body", body);
        pushRecent(row);
    }

    private void appendEvent(String level, String queue, String text) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("seq", consumedTotal.get());
        row.put("time", LocalDateTime.now().format(TS));
        row.put("level", level);
        row.put("queue", queue);
        row.put("messageId", null);
        row.put("body", text);
        pushRecent(row);
    }

    private void pushRecent(Map<String, Object> row) {
        recent.addFirst(row);
        while (recent.size() > MAX_RECENT) {
            recent.pollLast();
        }
    }

    private static List<String> resolveQueues(CnccqEndpointConfig cfg, QueueSide side) {
        List<String> list = new ArrayList<String>();
        if (side == QueueSide.INBOUND || side == QueueSide.BOTH) {
            if (!isBlank(cfg.getInboundQueue())) {
                list.add(cfg.getInboundQueue().trim());
            }
        }
        if (side == QueueSide.OUTBOUND || side == QueueSide.BOTH) {
            if (!isBlank(cfg.getOutboundQueue())) {
                list.add(cfg.getOutboundQueue().trim());
            }
        }
        return list;
    }

    private static CnccqJmsConnectionPooledFactory openFactory(CnccqEndpointConfig cfg) {
        String host = cfg.getHost().trim();
        int port = cfg.getPort();
        String qm = cfg.getQueueManager().trim();
        int pool = Math.max(1, cfg.getPoolSize());
        long tcpWait = cfg.getTcpWaitTimeMs() > 0 ? cfg.getTcpWaitTimeMs() : 60_000L;
        Charset charset = StandardCharsets.UTF_8;
        if (!isBlank(cfg.getUsername())) {
            return new CnccqJmsConnectionPooledFactory(
                    host, port, qm, pool, tcpWait, charset,
                    cfg.getUsername().trim(),
                    cfg.getPassword() == null ? "" : cfg.getPassword());
        }
        return new CnccqJmsConnectionPooledFactory(host, port, qm, pool, tcpWait, charset);
    }

    private static String extractBody(CnccqMessage msg) {
        if (msg == null) {
            return "";
        }
        try {
            String text = msg.getText();
            if (text != null && !text.isEmpty()) {
                return truncate(text);
            }
        } catch (Exception ignored) {
            // fall through to bytes
        }
        ByteSequence content = msg.getContent();
        if (content == null || content.getData() == null) {
            return truncate(String.valueOf(msg));
        }
        try {
            String viaSeq = content.getText();
            if (viaSeq != null && !viaSeq.isEmpty()) {
                return truncate(viaSeq);
            }
        } catch (Exception ignored) {
            // fall through
        }
        byte[] data = content.getData();
        int len = content.getLength() > 0 ? content.getLength() : data.length;
        int n = Math.min(len, BODY_PREVIEW_MAX);
        byte[] buf = new byte[n];
        System.arraycopy(data, 0, buf, 0, n);
        String text = decodeBytes(buf);
        if (len > BODY_PREVIEW_MAX) {
            text = text + "\n…(截断，原始 " + len + " bytes)";
        }
        return text;
    }

    private static String decodeBytes(byte[] buf) {
        String s = new String(buf, StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0) {
            return new String(buf, Charset.forName("GBK"));
        }
        return s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= BODY_PREVIEW_MAX) {
            return s;
        }
        return s.substring(0, BODY_PREVIEW_MAX) + "\n…(截断)";
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        String msg = e.getMessage();
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                msg = cur.getMessage();
            }
        }
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(CnccqJmsConsumer c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void closeQuietly(CnccqJmsContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
