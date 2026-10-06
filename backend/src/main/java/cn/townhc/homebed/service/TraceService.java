package cn.townhc.homebed.service;

import cn.townhc.homebed.common.BusinessException;
import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 服务全链还原：对一次家庭病床服务，把申请、评估、排班、每次上门的
 * 签到/体征/照片/耗材/异常/医嘱/家属签字、账单、合规审查、风险事件
 * 按时间线组装，用于处理收费质疑、医保核查与纠纷举证。
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final BedApplicationRepository applicationRepository;
    private final CareNeedRepository careNeedRepository;
    private final VisitPlanRepository planRepository;
    private final VisitRepository visitRepository;
    private final VisitCareResultRepository careResultRepository;
    private final VisitPhotoRepository photoRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final MaterialTransactionRepository materialTxRepository;
    private final MedicalOrderRepository orderRepository;
    private final MaterialSubstituteRepository substituteRepository;
    private final SubstituteTrackingRepository substituteTrackingRepository;
    private final MaterialReplenishTaskRepository replenishRepository;
    private final CarePlanAdjustmentRepository adjustmentRepository;
    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final ComplianceRecordRepository complianceRepository;
    private final RiskEventRepository riskRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> byApplication(Long applicationId) {
        BedApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(404, "申请单不存在"));
        Long patientId = app.getPatient().getId();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("patient", app.getPatient());
        root.put("application", app);
        root.put("careNeeds", careNeedRepository.findByApplicationId(applicationId));

        List<VisitPlan> plans = planRepository.findByApplicationId(applicationId);
        root.put("plans", plans);

        List<Map<String, Object>> visitChain = new ArrayList<>();
        for (VisitPlan plan : plans) {
            for (Visit v : visitRepository.findByPlanIdOrderByScheduledAtAsc(plan.getId())) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("visit", v);
                node.put("careResults", careResultRepository.findByVisitId(v.getId()));
                node.put("photos", photoRepository.findByVisitId(v.getId()));
                node.put("exceptions", exceptionRepository.findByVisitId(v.getId()));
                node.put("materialTransactions", materialTxRepository.findByVisitId(v.getId()));
                node.put("orders", orderRepository.findByVisitId(v.getId()));
                node.put("substitutes", substituteRepository.findByVisitId(v.getId()));
                visitChain.add(node);
            }
        }
        root.put("visitChain", visitChain);

        // 耗材缺货替代确认（含效果追踪与照片）、补货任务、护理计划调整
        List<MaterialSubstitute> substitutes = substituteRepository
                .findByPatientIdOrderByCreatedAtDesc(patientId);
        List<Map<String, Object>> substituteChain = new ArrayList<>();
        for (MaterialSubstitute s : substitutes) {
            Map<String, Object> sm = new LinkedHashMap<>();
            initSubstitute(s);
            sm.put("substitute", s);
            List<SubstituteTracking> trs = substituteTrackingRepository
                    .findBySubstituteIdOrderByRecordedAtAsc(s.getId());
            trs.forEach(t -> {
                if (t.getRecorder() != null) org.hibernate.Hibernate.initialize(t.getRecorder());
            });
            sm.put("trackings", trs);
            substituteChain.add(sm);
        }
        root.put("substitutes", substituteChain);
        root.put("replenishTasks", replenishRepository.findByPatientIdOrderByCreatedAtDesc(patientId));
        root.put("careAdjustments", adjustmentRepository.findByPatientIdOrderByCreatedAtDesc(patientId));

        List<Map<String, Object>> bills = new ArrayList<>();
        for (Bill b : billRepository.findByApplicationId(applicationId)) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("bill", b);
            bm.put("items", billItemRepository.findByBillId(b.getId()));
            bm.put("compliance", complianceRepository.findByBillId(b.getId()).orElse(null));
            bills.add(bm);
        }
        root.put("bills", bills);
        root.put("riskEvents", riskRepository.findByPatientIdOrderByReportedAtDesc(patientId));

        // 审计时间线（申请单维度 + 患者维度合并）
        List<AuditLog> logs = new ArrayList<>();
        logs.addAll(auditLogRepository.findByBizNoOrderByOccurredAtAsc(app.getApplyNo()));
        Set<Long> seen = new HashSet<>();
        for (AuditLog l : logs) seen.add(l.getId());
        for (AuditLog l : auditLogRepository.findByPatientIdOrderByOccurredAtDesc(patientId)) {
            if (seen.add(l.getId())) logs.add(l);
        }
        logs.sort(Comparator.comparing(AuditLog::getOccurredAt));
        root.put("timeline", buildTimeline(app, visitChain, substituteChain, bills, logs));
        root.put("auditLogs", logs);
        return root;
    }

    /** 输出结构化时间线（阶段/时间/角色/事件/证据） */
    private List<Map<String, Object>> buildTimeline(BedApplication app,
                                                    List<Map<String, Object>> visitChain,
                                                    List<Map<String, Object>> substituteChain,
                                                    List<Map<String, Object>> bills,
                                                    List<AuditLog> logs) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        add(timeline, app.getCreatedAt(), "申请", "家属", "提交家庭病床申请 " + app.getApplyNo(),
                app.getApplyReason(), null);
        if (app.getAssessedAt() != null) {
            add(timeline, app.getAssessedAt(), "评估", "家庭医生·" + safeName(app.getDoctor()),
                    "评估" + app.getStatus().label + "，风险" + (app.getRiskLevel() == null ? "-" : app.getRiskLevel().label),
                    "距离 " + app.getDistanceMeters() + " 米；照护人：" + app.getCaregiverCondition()
                            + "；医保资格：" + (Boolean.TRUE.equals(app.getInsuranceEligible()) ? "符合" : "不符合")
                            + "；结论：" + app.getAssessmentConclusion(), null);
        }
        for (Map<String, Object> node : visitChain) {
            Visit v = (Visit) node.get("visit");
            if (v.getStartedAt() != null) {
                add(timeline, v.getStartedAt(), "上门", "护士·" + safeName(v.getNurse()),
                        v.getVisitNo() + " 签到上门",
                        "位置：" + v.getNurseLocationSnapshot(), null);
            }
            if (v.getCompletedAt() != null) {
                @SuppressWarnings("unchecked")
                List<VisitPhoto> photos = (List<VisitPhoto>) node.get("photos");
                @SuppressWarnings("unchecked")
                List<VisitCareResult> results = (List<VisitCareResult>) node.get("careResults");
                @SuppressWarnings("unchecked")
                List<MaterialTransaction> txs = (List<MaterialTransaction>) node.get("materialTransactions");
                add(timeline, v.getCompletedAt(), "巡诊记录", "护士·" + safeName(v.getNurse()),
                        v.getVisitNo() + " 完成护理 " + results.size() + " 项、领用耗材 " + txs.size() + " 项",
                        "血压 " + v.getSystolicPressure() + "/" + v.getDiastolicPressure()
                                + "；血糖 " + v.getBloodGlucose()
                                + "；压疮：" + nz(v.getPressureUlcerNote())
                                + "；换药：" + nz(v.getDressingNote())
                                + "；导尿：" + nz(v.getCatheterNote())
                                + "；用药：" + nz(v.getMedicationNote()),
                        photos.stream().map(VisitPhoto::getFilePath).toList());
                add(timeline, v.getFamilySignedAt(), "家属签字", app.getPatient().getPrimaryCaregiver(),
                        v.getVisitNo() + " 家属确认签字：" + v.getFamilySignName(),
                        "家属对本次服务项目与耗材现场确认",
                        v.getFamilySignPath() == null ? List.of() : List.of(v.getFamilySignPath()));
            }
            @SuppressWarnings("unchecked")
            List<MedicalOrder> orders = (List<MedicalOrder>) node.get("orders");
            for (MedicalOrder o : orders) {
                add(timeline, o.getCreatedAt(), "医嘱", "医生·" + safeName(o.getDoctor()),
                        o.getOrderNo() + " " + o.getOrderType().label,
                        o.getContent(), null);
                if (o.getReviewedAt() != null) {
                    add(timeline, o.getReviewedAt(), "远程复核", "医生·" + safeName(o.getReviewer()),
                            o.getOrderNo() + " 远程复核" + o.getStatus().label, o.getReviewComment(), null);
                }
            }
            @SuppressWarnings("unchecked")
            List<VisitException> exs = (List<VisitException>) node.get("exceptions");
            for (VisitException e : exs) {
                add(timeline, e.getCreatedAt(), "异常", e.getType().label,
                        v.getVisitNo() + " " + e.getType().label, e.getDescription()
                                + (e.getHandleConclusion() != null ? "；处理：" + e.getHandleConclusion() : ""),
                        e.getEvidencePath() == null ? List.of() : List.of(e.getEvidencePath()));
            }
            @SuppressWarnings("unchecked")
            List<MaterialSubstitute> subs = (List<MaterialSubstitute>) node.get("substitutes");
            if (subs != null) {
                for (MaterialSubstitute s : subs) {
                    addSubstituteTimeline(timeline, s);
                }
            }
        }
        // 非挂在巡诊节点下的替代单（如计划阶段发起）补齐
        java.util.Set<Long> inChain = new java.util.HashSet<>();
        for (Map<String, Object> node : visitChain) {
            @SuppressWarnings("unchecked")
            List<MaterialSubstitute> subs = (List<MaterialSubstitute>) node.get("substitutes");
            if (subs != null) subs.forEach(s -> inChain.add(s.getId()));
        }
        for (Map<String, Object> bm2 : substituteChain) {
            MaterialSubstitute s = (MaterialSubstitute) bm2.get("substitute");
            if (!inChain.contains(s.getId())) addSubstituteTimeline(timeline, s);
        }
        for (Map<String, Object> bm : bills) {
            Bill b = (Bill) bm.get("bill");
            add(timeline, b.getCreatedAt(), "账单", "财务",
                    b.getBillNo() + " 统一账单生成（" + b.getStatus().label + "）",
                    "总额 " + b.getTotalAmount() + "，医保 " + b.getInsuranceAmount()
                            + "，自费 " + b.getSelfPayAmount(), null);
            if (b.getFamilyConfirmedAt() != null) {
                add(timeline, b.getFamilyConfirmedAt(), "家属确认", "家属·" + nz(b.getFamilyConfirmNote()),
                        b.getBillNo() + " 家属确认费用明细", b.getFamilyConfirmNote(), null);
            }
            if (b.getDisputedAt() != null) {
                add(timeline, b.getDisputedAt(), "收费质疑", "家属",
                        b.getBillNo() + " 家属质疑收费", b.getDisputeNote(), null);
            }
            if (b.getSettledAt() != null) {
                add(timeline, b.getSettledAt(), "结算", "医保经办",
                        b.getBillNo() + " 医保结算，批次 " + b.getInsuranceSettleNo(), null, null);
            }
        }
        timeline.sort(Comparator.comparing(m -> (java.time.LocalDateTime) m.get("time")));
        return timeline;
    }

    private String nz(String s) { return s == null || s.isBlank() ? "-" : s; }
    private String safeName(SysUser u) { return u == null ? "-" : u.getName(); }

    private void initSubstitute(MaterialSubstitute s) {
        org.hibernate.Hibernate.initialize(s.getPatient());
        org.hibernate.Hibernate.initialize(s.getOriginalMaterial());
        org.hibernate.Hibernate.initialize(s.getSubstituteMaterial());
        if (s.getProposer() != null) org.hibernate.Hibernate.initialize(s.getProposer());
        if (s.getDoctor() != null) org.hibernate.Hibernate.initialize(s.getDoctor());
        if (s.getReviewDoctor() != null) org.hibernate.Hibernate.initialize(s.getReviewDoctor());
        if (s.getExecutedBy() != null) org.hibernate.Hibernate.initialize(s.getExecutedBy());
        if (s.getReviewOrder() != null) {
            org.hibernate.Hibernate.initialize(s.getReviewOrder());
            if (s.getReviewOrder().getDoctor() != null) org.hibernate.Hibernate.initialize(s.getReviewOrder().getDoctor());
        }
        if (s.getReplenishTask() != null) {
            org.hibernate.Hibernate.initialize(s.getReplenishTask());
            org.hibernate.Hibernate.initialize(s.getReplenishTask().getMaterial());
        }
        if (s.getCareAdjustment() != null) org.hibernate.Hibernate.initialize(s.getCareAdjustment());
    }

    /** 把一张缺货替代确认单的完整链路（申请→医生确认→家属签字→执行→追踪→回看调整/拒绝补货）写入时间线 */
    private void addSubstituteTimeline(List<Map<String, Object>> timeline, MaterialSubstitute s) {
        add(timeline, s.getCreatedAt(), "耗材替代", "护士·" + safeName(s.getProposer()),
                s.getSubstituteNo() + " 缺货替代申请",
                "原医嘱/方案：" + nz(s.getOriginalUsageSnapshot())
                        + "\n原品：" + s.getOriginalMaterial().getName()
                        + nz(s.getOriginalMaterial().getSpec()) + "（库存不足" + nz(s.getShortageNote()) + "）"
                        + "\n替代：" + s.getSubstituteMaterial().getName()
                        + nz(s.getSubstituteMaterial().getSpec())
                        + "\n医保差异：总费用 " + s.getTotalDiff() + "、医保报销 " + s.getInsuranceDiff()
                        + "、家属自费 " + s.getSelfPayDiff()
                        + "\n可能影响：" + nz(s.getPossibleImpact()),
                null);
        if (s.getDoctorConfirmedAt() != null) {
            add(timeline, s.getDoctorConfirmedAt(), "耗材替代", "医生·" + safeName(s.getDoctor()),
                    s.getSubstituteNo() + (Boolean.TRUE.equals(s.getDoctorApproved())
                            ? " 医生确认同意替代" : " 医生不予替代"),
                    s.getDoctorComment(), null);
        }
        if (s.getFamilySignedAt() != null) {
            boolean agreed = s.getStatus() != cn.townhc.homebed.domain.enums.DomainEnums.SubstituteStatus.FAMILY_REJECTED;
            add(timeline, s.getFamilySignedAt(), "家属签字", s.getFamilySignName(),
                    s.getSubstituteNo() + (agreed ? " 家属签字同意替代" : " 家属不同意替代"),
                    (agreed ? "家属知情同意按替代材料执行" : "家属要求继续使用原品，系统已生成补货任务并调整本次护理计划")
                            + "；意见：" + nz(s.getFamilyDecisionNote()),
                    s.getFamilySignPath() == null ? List.of() : List.of(s.getFamilySignPath()));
        }
        if (s.getExecutedAt() != null) {
            add(timeline, s.getExecutedAt(), "耗材替代", "护士·" + safeName(s.getExecutedBy()),
                    s.getSubstituteNo() + " 替代材料已执行",
                    s.getSubstituteMaterial().getName() + " 领用 " + s.getQuantity()
                            + s.getSubstituteMaterial().getUnit()
                            + (s.getBatchNo() != null ? "，批号 " + s.getBatchNo() : "")
                            + "；" + nz(s.getExecutionNote()), null);
        }
        for (SubstituteTracking t : substituteTrackingRepository
                .findBySubstituteIdOrderByRecordedAtAsc(s.getId())) {
            List<String> photos = photoRepository.findAll().stream()
                    .filter(p -> p.getSubstitute() != null && p.getSubstitute().getId().equals(s.getId())
                            && p.getCreatedAt() != null
                            && (t.getRecordedAt() == null || !p.getCreatedAt().isAfter(t.getRecordedAt())))
                    .map(VisitPhoto::getFilePath).toList();
            add(timeline, t.getRecordedAt(), "替代追踪", safeName(t.getRecorder()),
                    s.getSubstituteNo() + " 伤口效果：" + t.getOutcome().label,
                    nz(t.getNote()), photos.isEmpty() ? null : photos);
        }
        if (s.getReviewedAt() != null) {
            add(timeline, s.getReviewedAt(), "耗材替代", "医生·" + safeName(s.getReviewDoctor()),
                    s.getSubstituteNo() + " 医生回看替代原因并调整护理材料",
                    "回看结论：" + nz(s.getReviewComment())
                            + "\n后续护理材料调整：" + nz(s.getReviewAction())
                            + (s.getReviewOrder() != null ? "\n已下医嘱 " + s.getReviewOrder().getOrderNo() : ""),
                    null);
        }
        if (s.getReplenishTask() != null) {
            MaterialReplenishTask t = s.getReplenishTask();
            add(timeline, t.getCreatedAt(), "补货任务", "系统/护士",
                    t.getTaskNo() + " 补货任务（" + t.getStatus().label + "）",
                    t.getMaterial().getName() + " x" + t.getRequiredQty()
                            + "；原因：" + nz(t.getReason())
                            + (t.getArrivedAt() != null ? "；已于 " + t.getArrivedAt() + " 到货入库" : ""),
                    null);
        }
        if (s.getCareAdjustment() != null) {
            CarePlanAdjustment a = s.getCareAdjustment();
            add(timeline, a.getCreatedAt(), "护理计划调整", "系统",
                    a.getAdjustNo() + " 本次护理计划调整（" + a.getStatus().label + "）",
                    "调整：" + nz(a.getAdjustmentContent())
                            + "；计划补做：" + a.getExpectedMakeupAt()
                            + (a.getCompletedAt() != null ? "；已完成补做：" + a.getCompletionNote() : ""),
                    null);
        }
    }

    private void add(List<Map<String, Object>> timeline, java.time.LocalDateTime time,
                     String stage, String actor, String title, String detail, List<String> evidences) {
        if (time == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("stage", stage);
        m.put("actor", actor);
        m.put("title", title);
        m.put("detail", detail);
        m.put("evidences", evidences == null ? List.of() : evidences);
        timeline.add(m);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> auditLogs(String module) {
        if (module != null && !module.isBlank()) {
            return auditLogRepository.findByModuleOrderByOccurredAtDesc(module);
        }
        return auditLogRepository.findTop200ByOrderByOccurredAtDesc();
    }
}
