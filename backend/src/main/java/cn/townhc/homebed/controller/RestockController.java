package cn.townhc.homebed.controller;

import cn.townhc.homebed.common.ApiResponse;
import cn.townhc.homebed.domain.entity.RestockTask;
import cn.townhc.homebed.service.RestockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "耗材补货任务")
@RestController
@RequestMapping("/api/restock-tasks")
@RequiredArgsConstructor
public class RestockController {

    private final RestockService restockService;

    @GetMapping
    public ApiResponse<List<RestockTask>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(restockService.list(status));
    }

    @Operation(summary = "手动创建补货任务（缺货预警/异常）")
    @PostMapping
    public ApiResponse<RestockTask> create(@RequestBody Map<String, Object> body) {
        Long materialId = Long.valueOf(body.get("materialId").toString());
        Integer qty = body.get("requiredQty") == null ? null
                : Integer.valueOf(body.get("requiredQty").toString());
        Boolean urgent = body.get("urgent") == null ? null
                : Boolean.valueOf(body.get("urgent").toString());
        return ApiResponse.ok(restockService.create(materialId, qty, urgent,
                (String) body.get("sourceNote")));
    }

    @Operation(summary = "报采购")
    @PostMapping("/{id}/order")
    public ApiResponse<RestockTask> order(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(restockService.order(id, body == null ? null : body.get("note")));
    }

    @Operation(summary = "到货入库（家属拒绝替代的，自动安排原品补换药上门）")
    @PostMapping("/{id}/arrive")
    public ApiResponse<RestockTask> arrive(@PathVariable Long id,
                                           @RequestBody RestockService.ArriveCommand cmd) {
        return ApiResponse.ok(restockService.arrive(id, cmd));
    }

    @Operation(summary = "取消补货任务")
    @PostMapping("/{id}/cancel")
    public ApiResponse<RestockTask> cancel(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(restockService.cancel(id, body == null ? null : body.get("note")));
    }
}
