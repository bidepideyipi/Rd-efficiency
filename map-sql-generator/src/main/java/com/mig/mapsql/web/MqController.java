package com.mig.mapsql.web;

import com.mig.mapsql.mq.MqConfigRepository;
import com.mig.mapsql.mq.MqConsumerService;
import com.mig.mapsql.mq.MqEndpointConfig;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mq")
public class MqController {

    private final MqConfigRepository repository;
    private final MqConsumerService consumerService;

    public MqController(MqConfigRepository repository, MqConsumerService consumerService) {
        this.repository = repository;
        this.consumerService = consumerService;
    }

    @GetMapping("/configs")
    public List<MqEndpointConfig> list() {
        return repository.listAll();
    }

    @GetMapping("/configs/{id}")
    public MqEndpointConfig get(@PathVariable("id") long id) {
        MqEndpointConfig cfg = repository.findById(id);
        if (cfg == null) {
            throw new IllegalArgumentException("MQ 配置不存在: id=" + id);
        }
        return cfg;
    }

    @PostMapping("/configs")
    public MqEndpointConfig create(@RequestBody MqEndpointConfig body) {
        body.setId(null);
        return repository.save(body);
    }

    @PutMapping("/configs/{id}")
    public MqEndpointConfig update(@PathVariable("id") long id, @RequestBody MqEndpointConfig body) {
        body.setId(id);
        return repository.save(body);
    }

    @DeleteMapping("/configs/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        Map<String, Object> status = consumerService.status();
        Object runningId = status.get("configId");
        if (Boolean.TRUE.equals(status.get("running"))
                && runningId != null
                && Long.parseLong(String.valueOf(runningId)) == id) {
            throw new IllegalArgumentException("该配置正在消费中，请先停止再删除");
        }
        repository.delete(id);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("id", id);
        return m;
    }

    @PostMapping("/configs/{id}/test")
    public Map<String, Object> test(@PathVariable("id") long id) {
        return consumerService.testConnect(id);
    }

    /** 查询队列积压（CURDEPTH），不消费消息。side=INBOUND|OUTBOUND|BOTH */
    @GetMapping("/configs/{id}/depth")
    public Map<String, Object> depth(@PathVariable("id") long id,
                                     @RequestParam(value = "side", required = false) String side) {
        return consumerService.inquireDepth(id, side);
    }

    @PostMapping("/consume/start")
    public Map<String, Object> start(@RequestBody StartRequest req) {
        if (req == null || req.configId == null) {
            throw new IllegalArgumentException("configId 必填");
        }
        return consumerService.start(req.configId, req.side);
    }

    @PostMapping("/consume/stop")
    public Map<String, Object> stop() {
        return consumerService.stop();
    }

    @GetMapping("/consume/status")
    public Map<String, Object> status() {
        return consumerService.status();
    }

    public static class StartRequest {
        public Long configId;
        /** INBOUND | OUTBOUND | BOTH */
        public String side;
    }
}
