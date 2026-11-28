package com.mig.mapsql.web;

import com.mig.mapsql.errorcode.ErrorCodeEntry;
import com.mig.mapsql.errorcode.ErrorCodeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/errorcode")
public class ErrorCodeController {

    private final ErrorCodeRepository repository;

    public ErrorCodeController(ErrorCodeRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return repository.summary();
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return repository.listCategories();
    }

    @GetMapping
    public Map<String, Object> search(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "message", required = false) String message) {
        List<ErrorCodeEntry> rows = repository.search(category, code, message);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("total", rows.size());
        body.put("rows", rows);
        return body;
    }
}
