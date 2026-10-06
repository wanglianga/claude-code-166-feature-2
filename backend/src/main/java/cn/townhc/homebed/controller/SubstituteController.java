package cn.townhc.homebed.controller;

import cn.townhc.homebed.common.ApiResponse;
import cn.townhc.homebed.domain.entity.CarePlanAdjustment;
import cn.townhc.homebed.domain.entity.MaterialReplenishTask;
import cn.townhc.homebed.domain.entity.MaterialSubstitute;
import cn.townhc.homebed.domain.entity.SubstituteTracking;
import cn.townhc.homebed.service.SubstituteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "耗材缺货替代确认（原医嘱/医保差异/医生确认/家属签字/效果追踪/补货联动）")
@RestController
@RequestMapping("/api/substitutes")
@RequiredArgsConstructor
public class SubstituteController {

    private final SubstituteService substituteService;

    @GetMapping
    @Operation(summary = "替代确认单列表（家属仅见本人患者）")
    public ApiResponse<List<MaterialSubstitute>> list(@RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Long patientId) {
        return ApiResponse.ok(substituteService.list(status, patientId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "替代确认单详情（含原医嘱/医保差异/签字/追踪照片/补货与计划调整）")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(substituteService.detail(id));
    }

    @PostMapping
    @Operation(summary = "护士发起缺货替代申请（固化原医嘱、替代规格、医保报销差异、可能影响）")
    public ApiResponse<MaterialSubstitute> propose(@RequestBody SubstituteService.ProposeCommand cmd) {
        return ApiResponse.ok(substituteService.propose(cmd));
    }

    @PostMapping("/{id}/doctor-confirm")
    @Operation(summary = "医生确认是否同意使用替代材料")
    public ApiResponse<MaterialSubstitute> doctorConfirm(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(substituteService.doctorConfirm(id,
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("approved", true))),
                (String) body.get("comment")));
    }

    @PostMapping("/{id}/family-decide")
    @Operation(summary = "家属知情签字：同意→待执行；不同意→自动生成补货任务并调整本次护理计划")
    public ApiResponse<MaterialSubstitute> familyDecide(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(substituteService.familyDecide(id,
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("agreed", true))),
                (String) body.get("signName"), (String) body.get("signPath"),
                (String) body.get("note")));
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "执行替代材料（扣库存+替代出库流水，照片作为首次追踪证据）")
    public ApiResponse<MaterialSubstitute> execute(@PathVariable Long id,
                                                   @RequestBody(required = false) SubstituteService.ExecuteCommand cmd) {
        return ApiResponse.ok(substituteService.execute(id, cmd));
    }

    @PostMapping("/{id}/tracking")
    @Operation(summary = "后续上门登记伤口效果追踪（愈合正常/变慢/过敏+伤口照片；异常自动风险预警）")
    public ApiResponse<SubstituteTracking> tracking(@PathVariable Long id,
                                                    @RequestBody SubstituteService.TrackingCommand cmd) {
        return ApiResponse.ok(substituteService.addTracking(id, cmd));
    }

    @PostMapping("/{id}/doctor-review")
    @Operation(summary = "医生回看替代原因，调整后续护理材料（可同步生成护理方案调整医嘱）")
    public ApiResponse<MaterialSubstitute> doctorReview(@PathVariable Long id,
                                                        @RequestBody SubstituteService.ReviewCommand cmd) {
        return ApiResponse.ok(substituteService.doctorReview(id, cmd));
    }

    // ===== 补货任务 =====

    @GetMapping("/replenish/list")
    @Operation(summary = "补货任务列表")
    public ApiResponse<List<MaterialReplenishTask>> replenishList(@RequestParam(required = false) String status) {
        return ApiResponse.ok(substituteService.replenishList(status));
    }

    @PostMapping("/replenish")
    @Operation(summary = "基于缺货预警/异常手工新建补货任务")
    public ApiResponse<MaterialReplenishTask> createReplenish(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(substituteService.createReplenish(
                Long.valueOf(body.get("materialId").toString()),
                body.get("requiredQty") == null ? null : Integer.valueOf(body.get("requiredQty").toString()),
                (String) body.get("reason"),
                body.get("patientId") == null ? null : Long.valueOf(body.get("patientId").toString())));
    }

    @PostMapping("/replenish/{taskId}/order")
    @Operation(summary = "补货下单/催货（登记预计到货）")
    public ApiResponse<MaterialReplenishTask> orderReplenish(@PathVariable Long taskId,
                                                             @RequestBody Map<String, Object> body) {
        LocalDateTime eta = body.get("expectedArrivalAt") == null ? null
                : LocalDateTime.parse(body.get("expectedArrivalAt").toString());
        return ApiResponse.ok(substituteService.orderReplenish(taskId,
                (String) body.get("note"), eta));
    }

    @PostMapping("/replenish/{taskId}/arrive")
    @Operation(summary = "补货到货入库（自动增加库存并写入库流水）")
    public ApiResponse<MaterialReplenishTask> arriveReplenish(@PathVariable Long taskId,
                                                              @RequestBody SubstituteService.ArriveCommand cmd) {
        return ApiResponse.ok(substituteService.arriveReplenish(taskId, cmd));
    }

    // ===== 护理计划调整 =====

    @GetMapping("/adjustments/list")
    @Operation(summary = "拒绝替代后的护理计划调整列表")
    public ApiResponse<List<CarePlanAdjustment>> adjustmentList(@RequestParam(required = false) String status) {
        return ApiResponse.ok(substituteService.adjustmentList(status));
    }

    @PostMapping("/adjustments/{adjustmentId}/schedule")
    @Operation(summary = "安排原品到货后补做时间/调整措施")
    public ApiResponse<CarePlanAdjustment> scheduleMakeup(@PathVariable Long adjustmentId,
                                                          @RequestBody Map<String, Object> body) {
        LocalDateTime at = body.get("expectedMakeupAt") == null ? null
                : LocalDateTime.parse(body.get("expectedMakeupAt").toString());
        return ApiResponse.ok(substituteService.scheduleMakeup(adjustmentId, at,
                (String) body.get("content")));
    }

    @PostMapping("/adjustments/{adjustmentId}/complete")
    @Operation(summary = "补做完成登记（联动关闭补货任务）")
    public ApiResponse<CarePlanAdjustment> completeAdjustment(@PathVariable Long adjustmentId,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        Long visitId = body == null || body.get("makeupVisitId") == null ? null
                : Long.valueOf(body.get("makeupVisitId").toString());
        String note = body == null ? null : (String) body.get("note");
        return ApiResponse.ok(substituteService.completeAdjustment(adjustmentId, visitId, note));
    }
}
