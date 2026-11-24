package com.mig.mapsql.mq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.jms.MQQueueConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.BytesMessage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台线程持续 MQGET；支持 来账 / 往账 / 两者；消息体写入环形缓冲供页面轮询。
 */
@Service
public class MqConsumerService {

    private static final Logger log = LoggerFactory.getLogger(MqConsumerService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RECENT = 200;
    private static final int BODY_PREVIEW_MAX = 4000;
    private static final long RECEIVE_TIMEOUT_MS = 800L;

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

    private final MqConfigRepository repository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong consumedTotal = new AtomicLong(0);
    private final ConcurrentLinkedDeque<Map<String, Object>> recent = new ConcurrentLinkedDeque<Map<String, Object>>();

    private volatile Long activeConfigId;
    private volatile String activeConfigName;
    private volatile QueueSide activeSide = QueueSide.BOTH;
    private volatile String lastError;
    private volatile String startedAt;
    private volatile Thread worker;

    public MqConsumerService(MqConfigRepository repository) {
        this.repository = repository;
    }

    public synchronized Map<String, Object> start(long configId, String sideRaw) {
        if (running.get()) {
            throw new IllegalArgumentException("已在消费中，请先停止（配置 id=" + activeConfigId + "）");
        }
        MqEndpointConfig cfg = repository.findById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("MQ 配置不存在: id=" + configId);
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

        final MqEndpointConfig snapshot = cfg;
        final List<String> queueNames = queues;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                consumeLoop(snapshot, queueNames);
            }
        }, "mq-consumer-" + configId);
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
        MqEndpointConfig cfg = repository.findById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("MQ 配置不存在: id=" + configId);
        }
        Connection conn = null;
        try {
            conn = openConnection(cfg);
            conn.start();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("message", "连接成功");
            m.put("host", cfg.getHost());
            m.put("port", cfg.getPort());
            m.put("queueManager", cfg.getQueueManager());
            m.put("channel", cfg.getChannel());
            return m;
        } catch (Exception e) {
            throw new IllegalArgumentException("连接失败: " + rootMessage(e), e);
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 查询队列当前深度（积压 CURDEPTH）。使用 MQOO_INQUIRE，不取消息。
     *
     * @param sideRaw INBOUND | OUTBOUND | BOTH，空则查配置中有名的队列
     */
    public Map<String, Object> inquireDepth(long configId, String sideRaw) {
        MqEndpointConfig cfg = repository.findById(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("MQ 配置不存在: id=" + configId);
        }
        QueueSide side = QueueSide.parse(sideRaw);
        List<String> queues = resolveQueues(cfg, side);
        if (queues.isEmpty()) {
            throw new IllegalArgumentException("所选侧无可用队列名，请检查配置");
        }

        MQQueueManager qmgr = null;
        List<Map<String, Object>> depths = new ArrayList<Map<String, Object>>();
        try {
            qmgr = openQueueManager(cfg);
            for (String qName : queues) {
                depths.add(inquireOneQueue(qmgr, qName));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("查询积压失败: " + rootMessage(e), e);
        } finally {
            disconnectQuietly(qmgr);
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

    private static Map<String, Object> inquireOneQueue(MQQueueManager qmgr, String qName) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("queue", qName);
        MQQueue queue = null;
        try {
            int options = CMQC.MQOO_INQUIRE | CMQC.MQOO_FAIL_IF_QUIESCING;
            queue = qmgr.accessQueue(qName, options);
            row.put("currentDepth", queue.getCurrentDepth());
            row.put("maximumDepth", queue.getMaximumDepth());
            row.put("openInputCount", queue.getOpenInputCount());
            row.put("openOutputCount", queue.getOpenOutputCount());
            row.put("ok", true);
        } catch (MQException e) {
            row.put("ok", false);
            row.put("currentDepth", null);
            row.put("error", "MQRC " + e.getReason() + ": " + e.getMessage());
        } catch (Exception e) {
            row.put("ok", false);
            row.put("currentDepth", null);
            row.put("error", rootMessage(e));
        } finally {
            if (queue != null) {
                try {
                    queue.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return row;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MQQueueManager openQueueManager(MqEndpointConfig cfg) throws MQException {
        Hashtable props = new Hashtable();
        props.put(CMQC.HOST_NAME_PROPERTY, cfg.getHost().trim());
        props.put(CMQC.PORT_PROPERTY, Integer.valueOf(cfg.getPort()));
        props.put(CMQC.CHANNEL_PROPERTY, cfg.getChannel().trim());
        props.put(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);
        if (!isBlank(cfg.getUsername())) {
            props.put(CMQC.USER_ID_PROPERTY, cfg.getUsername().trim());
            props.put(CMQC.PASSWORD_PROPERTY, cfg.getPassword() == null ? "" : cfg.getPassword());
        }
        return new MQQueueManager(cfg.getQueueManager().trim(), props);
    }

    private static void disconnectQuietly(MQQueueManager qmgr) {
        if (qmgr == null) {
            return;
        }
        try {
            qmgr.disconnect();
        } catch (Exception ignored) {
            // ignore
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            stop();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void consumeLoop(MqEndpointConfig cfg, List<String> queueNames) {
        Connection connection = null;
        Session session = null;
        List<MessageConsumer> consumers = new ArrayList<MessageConsumer>();
        try {
            connection = openConnection(cfg);
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            for (String q : queueNames) {
                consumers.add(session.createConsumer(session.createQueue(q)));
            }
            connection.start();
            appendEvent("INFO", null, "已连接并开始消费: " + String.join(", ", queueNames));

            while (running.get()) {
                boolean got = false;
                for (int i = 0; i < consumers.size(); i++) {
                    if (!running.get()) {
                        break;
                    }
                    MessageConsumer consumer = consumers.get(i);
                    String qName = queueNames.get(i);
                    try {
                        Message msg = consumer.receive(RECEIVE_TIMEOUT_MS);
                        if (msg != null) {
                            got = true;
                            handleMessage(qName, msg);
                        }
                    } catch (JMSException je) {
                        if (!running.get()) {
                            break;
                        }
                        lastError = rootMessage(je);
                        log.warn("MQ 收取失败: {}", lastError);
                        appendEvent("ERROR", qName, lastError);
                        sleepQuiet(1000L);
                    }
                }
                if (!got && running.get()) {
                    // 短歇，避免空转过密
                    sleepQuiet(50L);
                }
            }
            appendEvent("INFO", null, "消费已停止");
        } catch (Exception e) {
            lastError = rootMessage(e);
            log.error("MQ 消费异常", e);
            appendEvent("ERROR", null, lastError);
        } finally {
            for (MessageConsumer c : consumers) {
                closeQuietly(c);
            }
            closeQuietly(session);
            closeQuietly(connection);
            running.set(false);
        }
    }

    private void handleMessage(String queueName, Message msg) throws JMSException {
        long n = consumedTotal.incrementAndGet();
        String body = extractBody(msg);
        String msgId = msg.getJMSMessageID();
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

    private static List<String> resolveQueues(MqEndpointConfig cfg, QueueSide side) {
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

    private static Connection openConnection(MqEndpointConfig cfg) throws Exception {
        MQQueueConnectionFactory cf = new MQQueueConnectionFactory();
        cf.setHostName(cfg.getHost().trim());
        cf.setPort(cfg.getPort());
        cf.setQueueManager(cfg.getQueueManager().trim());
        cf.setChannel(cfg.getChannel().trim());
        cf.setTransportType(WMQConstants.WMQ_CM_CLIENT);

        if (!isBlank(cfg.getUsername())) {
            return cf.createConnection(cfg.getUsername().trim(),
                    cfg.getPassword() == null ? "" : cfg.getPassword());
        }
        return cf.createConnection();
    }

    private static String extractBody(Message msg) throws JMSException {
        if (msg instanceof TextMessage) {
            String text = ((TextMessage) msg).getText();
            return truncate(text == null ? "" : text);
        }
        if (msg instanceof BytesMessage) {
            BytesMessage bm = (BytesMessage) msg;
            long len = bm.getBodyLength();
            int n = (int) Math.min(len, BODY_PREVIEW_MAX);
            byte[] buf = new byte[n];
            bm.readBytes(buf);
            String text = decodeBytes(buf);
            if (len > BODY_PREVIEW_MAX) {
                text = text + "\n…(截断，原始 " + len + " bytes)";
            }
            return text;
        }
        return truncate(String.valueOf(msg));
    }

    private static String decodeBytes(byte[] buf) {
        // 优先 UTF-8；非法序列回退 ISO-8859-1 以保证可展示
        Charset utf8 = StandardCharsets.UTF_8;
        String s = new String(buf, utf8);
        if (s.indexOf('\uFFFD') >= 0) {
            return new String(buf, StandardCharsets.ISO_8859_1);
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

    private static void closeQuietly(MessageConsumer c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void closeQuietly(Session s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
