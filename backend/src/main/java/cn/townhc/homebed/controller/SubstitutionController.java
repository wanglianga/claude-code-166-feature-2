package cn.townhc.homebed.controller;

import cn.townhc.homebed.common.ApiResponse;
import cn.townhc.homebed.domain.entity.MaterialSubstitution;
import cn.townhc.homebed.domain.entity.WoundFollowUp;
import cn.townhc.homebed.service.SubstitutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "耗材缺货替代确认")
@RestController
@RequestMapping("/api/substitutions")
@RequiredArgsConstructor
public class SubstitutionController {

    private final SubstitutionService substitutionService;

    @GetMapping
    public ApiResponse<List<MaterialSubstitution>> list(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Long patientId,
                                                        @RequestParam(required = false) Long visitId) {
        return ApiResponse.ok(substitutionService.list(status, patientId, visitId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(substitutionService.detail(id));
    }

    @Operation(summary = "查询替代候选（规格/医保报销差异/可能影响测算）")
    @GetMapping("/candidates")
    public ApiResponse<List<Map<String, Object>>> candidates(@RequestParam Long visitId,
                                                             @RequestParam Long originalMaterialId) {
        return ApiResponse.ok(substitutionService.candidates(visitId, originalMaterialId));
    }

    @Operation(summary = "护士发起缺货替代申请（含原医嘱、缺货原因、替代材料）")
    @PostMapping
    public ApiResponse<MaterialSubstitution> propose(@RequestBody SubstitutionService.ProposeCommand cmd) {
        return ApiResponse.ok(substitutionService.propose(cmd));
    }

    @Operation(summary = "医生确认/退回替代方案")
    @PostMapping("/{id}/doctor-confirm")
    public ApiResponse<MaterialSubstitution> doctorConfirm(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(substitutionService.doctorConfirm(id,
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("approved", true))),
                (String) body.getOrDefault("comment", "")));
    }

    @Operation(summary = "家属知情决定：同意(手写签字)则执行，不同意则触发补货+护理计划调整")
    @PostMapping("/{id}/family-decide")
    public ApiResponse<MaterialSubstitution> familyDecide(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        boolean agreed = Boolean.parseBoolean(String.valueOf(body.getOrDefault("agreed", true)));
        return ApiResponse.ok(substitutionService.familyDecide(id, agreed,
                (String) body.get("signName"), (String) body.get("signPath"),
                (String) body.getOrDefault("comment", ""),
                (String) body.get("planAdjustment")));
    }

    @Operation(summary = "后续上门记录替代耗材伤口效果（照片+结论，异常自动生成风险事件）")
    @PostMapping("/follow-ups")
    public ApiResponse<WoundFollowUp> followUp(@RequestBody SubstitutionService.FollowUpCommand cmd) {
        return ApiResponse.ok(substitutionService.recordFollowUp(cmd));
    }

    @Operation(summary = "医生回看替代原因后调整后续护理材料（同步生成护理方案调整医嘱）")
    @PostMapping("/{id}/adjust-material")
    public ApiResponse<MaterialSubstitution> adjustMaterial(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(substitutionService.doctorAdjustMaterial(id, body.get("note")));
    }
}
