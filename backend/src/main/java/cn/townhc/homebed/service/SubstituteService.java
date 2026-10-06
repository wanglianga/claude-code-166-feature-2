package cn.townhc.homebed.service;

import cn.townhc.homebed.common.BusinessException;
import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.domain.enums.DomainEnums.*;
import cn.townhc.homebed.repository.*;
import cn.townhc.homebed.security.LoginUser;
import cn.townhc.homebed.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 耗材缺货替代确认服务。
 *
 * 流程：护士发起（固化原医嘱/规格/医保差异/可能影响）→ 医生确认 → 家属知情签字
 *   → 同意：护士执行替代品出库，后续上门凭伤口照片持续追踪效果；
 *           愈合变慢/过敏自动风险预警，医生可回看替代原因并调整后续护理材料（下护理方案医嘱）。
 *   → 不同意：自动生成补货任务并调整本次护理计划（暂缓/替代护理/到货补做），原医嘱材料不予替换。
 */
@Service
@RequiredArgsConstructor
public class SubstituteService {

    private final MaterialSubstituteRepository substituteRepository;
    private final SubstituteTrackingRepository trackingRepository;
    private final MaterialReplenishTaskRepository replenishRepository;
    private final CarePlanAdjustmentRepository adjustmentRepository;
    private final MaterialRepository materialRepository;
    private final MaterialTransactionRepository txRepository;
    private final VisitRepository visitRepository;
    private final VisitPlanRepository planRepository;
    private final VisitPhotoRepository photoRepository;
    private final MedicalOrderRepository orderRepository;
    private final BedApplicationRepository applicationRepository;
    private final PatientRepository patientRepository;
    private final RiskEventRepository riskRepository;
    private final SysUserRepository userRepository;
    private final AuditService auditService;

    // ===== DTO =====
    public record ProposeCommand(Long visitId, Long planId, Long originalMaterialId,
                                 Long substituteMaterialId, Integer quantity,
                                 String shortageNote, String possibleImpact, Long relatedOrderId) {}

    public record ExecuteCommand(String batchNo, String note, List<String> photoPaths) {}

    public record TrackingCommand(String outcome, String note, List<String> photoPaths, Long visitId) {}

    public record ReviewCommand(String comment, String action, boolean createOrder) {}

    // ===== 查询 =====

    @Transactional(readOnly = true)
    public List<MaterialSubstitute> list(String status, Long patientId) {
        LoginUser me = SecurityUtils.current();
        List<MaterialSubstitute> list;
        if (status != null && !status.isBlank()) {
            list = substituteRepository.findByStatusOrderByCreatedAtDesc(SubstituteStatus.valueOf(status));
        } else if (patientId != null) {
            list = substituteRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        } else {
            list = substituteRepository.findAllByOrderByCreatedAtDesc();
        }
        if (me.role() == Role.FAMILY) {
            list = list.stream().filter(s -> s.getPatient().getFamilyUser() != null
                    && s.getPatient().getFamilyUser().getId().equals(me.id())).toList();
        }
        list.forEach(this::initSubstituteAssociations);
        return list;
    }

    /** 列表/详情出参前显式初始化懒加载关联（Jackson 未开启强制加载） */
    public void initSubstituteAssociations(MaterialSubstitute s) {
        org.hibernate.Hibernate.initialize(s.getPatient());
        if (s.getPatient().getFamilyUser() != null) org.hibernate.Hibernate.initialize(s.getPatient().getFamilyUser());
        org.hibernate.Hibernate.initialize(s.getOriginalMaterial());
        org.hibernate.Hibernate.initialize(s.getSubstituteMaterial());
        if (s.getProposer() != null) org.hibernate.Hibernate.initialize(s.getProposer());
        if (s.getDoctor() != null) org.hibernate.Hibernate.initialize(s.getDoctor());
        if (s.getReviewDoctor() != null) org.hibernate.Hibernate.initialize(s.getReviewDoctor());
        if (s.getExecutedBy() != null) org.hibernate.Hibernate.initialize(s.getExecutedBy());
        if (s.getVisit() != null) org.hibernate.Hibernate.initialize(s.getVisit());
        if (s.getPlan() != null) org.hibernate.Hibernate.initialize(s.getPlan());
        if (s.getRelatedOrder() != null) org.hibernate.Hibernate.initialize(s.getRelatedOrder());
        if (s.getReviewOrder() != null) org.hibernate.Hibernate.initialize(s.getReviewOrder());
        if (s.getExecutedTx() != null) {
            org.hibernate.Hibernate.initialize(s.getExecutedTx());
            org.hibernate.Hibernate.initialize(s.getExecutedTx().getMaterial());
        }
        if (s.getReplenishTask() != null) {
            org.hibernate.Hibernate.initialize(s.getReplenishTask());
            org.hibernate.Hibernate.initialize(s.getReplenishTask().getMaterial());
        }
        if (s.getCareAdjustment() != null) org.hibernate.Hibernate.initialize(s.getCareAdjustment());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        MaterialSubstitute s = get(id);
        LoginUser me = SecurityUtils.current();
        if (me.role() == Role.FAMILY && (s.getPatient().getFamilyUser() == null
                || !s.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能查看本人相关确认单");
        }
        initSubstituteAssociations(s);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("substitute", s);
        List<SubstituteTracking> trackings = trackingRepository.findBySubstituteIdOrderByRecordedAtAsc(id);
        trackings.forEach(t -> {
            if (t.getRecorder() != null) org.hibernate.Hibernate.initialize(t.getRecorder());
            if (t.getVisit() != null) org.hibernate.Hibernate.initialize(t.getVisit());
        });
        map.put("trackings", trackings);
        map.put("photos", photoRepository.findAll().stream()
                .filter(p -> p.getSubstitute() != null && p.getSubstitute().getId().equals(id))
                .sorted(Comparator.comparing(BaseEntity::getCreatedAt))
                .toList());
        map.put("replenishTask", s.getReplenishTask());
        map.put("careAdjustment", s.getCareAdjustment());
        map.put("relatedOrder", s.getRelatedOrder());
        map.put("reviewOrder", s.getReviewOrder());
        map.put("executedTx", s.getExecutedTx());
        return map;
    }

    // ===== 护士发起替代 =====

    @Transactional
    public MaterialSubstitute propose(ProposeCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅护士可发起耗材替代申请");
        }
        Material original = materialRepository.findById(cmd.originalMaterialId())
                .orElseThrow(() -> new BusinessException(404, "原耗材不存在"));
        Material sub = materialRepository.findById(cmd.substituteMaterialId())
                .orElseThrow(() -> new BusinessException(404, "替代耗材不存在"));
        if (original.getId().equals(sub.getId())) {
            throw new BusinessException("替代材料不能与原医嘱材料相同");
        }
        int qty = cmd.quantity() == null || cmd.quantity() <= 0 ? 1 : cmd.quantity();
        if (original.getStockQty() != null && original.getStockQty() >= qty) {
            throw new BusinessException("原品[" + original.getName() + "]当前库存 "
                    + original.getStockQty() + original.getUnit() + "，尚不构成缺货，无需发起替代");
        }
        if (sub.getStockQty() == null || sub.getStockQty() < qty) {
            throw new BusinessException("替代品[" + sub.getName() + "]库存不足（" + sub.getStockQty() + sub.getUnit() + "）");
        }

        Visit visit = cmd.visitId() == null ? null : visitRepository.findById(cmd.visitId()).orElse(null);
        VisitPlan plan = visit != null ? visit.getPlan() : null;
        if (plan == null && cmd.planId() != null) {
            plan = planRepository.findById(cmd.planId()).orElse(null);
        }
        Patient patient = visit != null ? visit.getPatient()
                : plan != null ? plan.getPatient() : null;
        if (patient == null) {
            throw new BusinessException("缺少巡诊任务/护理计划，无法确定替代对象患者");
        }

        MaterialSubstitute s = new MaterialSubstitute();
        s.setSubstituteNo(ApplicationService.generateNo("TD"));
        s.setPatient(patient);
        s.setVisit(visit);
        s.setPlan(plan);
        s.setProposer(userRepository.findById(me.id()).orElse(null));
        s.setOriginalMaterial(original);
        s.setSubstituteMaterial(sub);
        s.setQuantity(qty);
        s.setShortageNote(cmd.shortageNote());
        s.setPossibleImpact(cmd.possibleImpact());

        // 关联原医嘱：显式指定 > 本次巡诊医嘱内容提及原品/品类的 > 该患者最近一条医嘱
        MedicalOrder related = null;
        if (cmd.relatedOrderId() != null) {
            related = orderRepository.findById(cmd.relatedOrderId()).orElse(null);
        }
        if (related == null && visit != null) {
            String key = materialKeyword(original);
            related = orderRepository.findByVisitId(visit.getId()).stream()
                    .filter(o -> o.getContent() != null && key != null && o.getContent().contains(key))
                    .findFirst().orElse(null);
        }
        if (related == null) {
            related = orderRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId()).stream()
                    .findFirst().orElse(null);
        }
        s.setRelatedOrder(related);
        StringBuilder snap = new StringBuilder();
        if (related != null) {
            snap.append("医嘱 ").append(related.getOrderNo()).append("（").append(related.getOrderType().label)
                    .append("）：").append(related.getContent());
        } else if (plan != null) {
            snap.append("护理计划 ").append(plan.getPlanNo()).append("：").append(plan.getPlanContent());
        } else {
            snap.append("按伤口换药护理常规使用：").append(original.getName())
                    .append(original.getSpec() == null ? "" : "（" + original.getSpec() + "）");
        }
        s.setOriginalUsageSnapshot(snap.toString());

        // 医保报销差异快照
        BedApplication app = latestApprovedApp(patient.getId());
        CostSnap o = costSnap(original, qty, patient, app);
        CostSnap n = costSnap(sub, qty, patient, app);
        s.setOrigUnitPrice(o.price); s.setOrigInsuranceCategory(o.insCat);
        s.setOrigReimbursementRate(o.rate); s.setOrigReimbursable(o.reimbursable);
        s.setOrigTotalAmount(o.total); s.setOrigInsuranceAmount(o.ins); s.setOrigSelfPayAmount(o.self);
        s.setSubUnitPrice(n.price); s.setSubInsuranceCategory(n.insCat);
        s.setSubReimbursementRate(n.rate); s.setSubReimbursable(n.reimbursable);
        s.setSubTotalAmount(n.total); s.setSubInsuranceAmount(n.ins); s.setSubSelfPayAmount(n.self);
        s.setTotalDiff(money(n.total.subtract(o.total)));
        s.setInsuranceDiff(money(n.ins.subtract(o.ins)));
        s.setSelfPayDiff(money(n.self.subtract(o.self)));
        s.setStatus(SubstituteStatus.PROPOSED);
        s = substituteRepository.save(s);

        auditService.log(me, "耗材替代", "护士发起替代申请", s.getId(), s.getSubstituteNo(),
                patient.getId(), "原品 " + original.getName() + "(库存" + original.getStockQty()
                        + ") → 替代 " + sub.getName() + "；总费用差 " + s.getTotalDiff()
                        + "，医保差 " + s.getInsuranceDiff() + "，自费差 " + s.getSelfPayDiff());
        return s;
    }

    // ===== 医生确认 =====

    @Transactional
    public MaterialSubstitute doctorConfirm(Long id, boolean approved, String comment) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅医生可确认替代材料");
        }
        MaterialSubstitute s = get(id);
        if (s.getStatus() != SubstituteStatus.PROPOSED) {
            throw new BusinessException("仅待医生确认的申请可操作，当前：" + s.getStatus().label);
        }
        s.setDoctor(userRepository.findById(me.id()).orElse(null));
        s.setDoctorApproved(approved);
        s.setDoctorComment(comment);
        s.setDoctorConfirmedAt(LocalDateTime.now());
        s.setStatus(approved ? SubstituteStatus.DOCTOR_CONFIRMED : SubstituteStatus.REVOKED);
        s = substituteRepository.save(s);
        auditService.log(me, "耗材替代", approved ? "医生确认同意替代" : "医生不予替代",
                s.getId(), s.getSubstituteNo(), s.getPatient().getId(),
                "原品 " + s.getOriginalMaterial().getName() + " → " + s.getSubstituteMaterial().getName()
                        + "；意见：" + comment);
        return s;
    }

    // ===== 家属签字决定 =====

    @Transactional
    public MaterialSubstitute familyDecide(Long id, boolean agreed, String signName,
                                           String signPath, String note) {
        LoginUser me = SecurityUtils.current();
        MaterialSubstitute s = get(id);
        if (me.role() != Role.FAMILY && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家属本人可签字确认（特殊情况由管理者代签留痕）");
        }
        if (me.role() == Role.FAMILY && (s.getPatient().getFamilyUser() == null
                || !s.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能对本人家属的替代单签字");
        }
        if (s.getStatus() != SubstituteStatus.DOCTOR_CONFIRMED) {
            throw new BusinessException("医生尚未确认同意，暂不能请家属签字");
        }
        if (signName == null || signName.isBlank() || signPath == null || signPath.isBlank()) {
            throw new BusinessException("无论同意与否，均需家属手写签名确认知情");
        }
        s.setFamilySignName(signName);
        s.setFamilySignPath(signPath);
        s.setFamilySignedAt(LocalDateTime.now());
        s.setFamilyDecisionNote(note);

        if (agreed) {
            s.setStatus(SubstituteStatus.FAMILY_AGREED);
            substituteRepository.save(s);
            auditService.log(me, "耗材替代", "家属签字同意替代", s.getId(), s.getSubstituteNo(),
                    s.getPatient().getId(), "签字人：" + signName + "；意见：" + note);
            return s;
        }

        // 家属不同意：不执行替代 → 生成补货任务 + 调整本次护理计划
        s.setStatus(SubstituteStatus.FAMILY_REJECTED);
        MaterialReplenishTask task = buildReplenishTask(s,
                "家属不同意使用替代材料[" + s.getSubstituteMaterial().getName()
                        + "]，要求按原医嘱使用[" + s.getOriginalMaterial().getName() + "]，紧急补货", me);
        CarePlanAdjustment adj = buildCareAdjustment(s);
        s.setReplenishTask(task);
        s.setCareAdjustment(adj);
        s = substituteRepository.save(s);
        auditService.log(me, "耗材替代", "家属拒绝替代·转补货与计划调整", s.getId(), s.getSubstituteNo(),
                s.getPatient().getId(), "签字人：" + signName + "；拒绝原因：" + note
                        + "；补货任务 " + task.getTaskNo() + "；护理调整 " + adj.getAdjustNo());
        return s;
    }

    private MaterialReplenishTask buildReplenishTask(MaterialSubstitute s, String reason, LoginUser me) {
        MaterialReplenishTask t = new MaterialReplenishTask();
        t.setTaskNo(ApplicationService.generateNo("BH"));
        t.setMaterial(s.getOriginalMaterial());
        t.setRequiredQty(Math.max(s.getQuantity() == null ? 1 : s.getQuantity(),
                s.getOriginalMaterial().getSafetyStock() == null ? 5 : s.getOriginalMaterial().getSafetyStock()));
        t.setReason(reason);
        t.setPatient(s.getPatient());
        t.setStatus(ReplenishStatus.PENDING);
        t.setCreator(userRepository.findById(me.id()).orElse(null));
        return replenishRepository.save(t);
    }

    private CarePlanAdjustment buildCareAdjustment(MaterialSubstitute s) {
        CarePlanAdjustment a = new CarePlanAdjustment();
        a.setAdjustNo(ApplicationService.generateNo("HL"));
        a.setPatient(s.getPatient());
        a.setVisit(s.getVisit());
        a.setPlan(s.getPlan());
        a.setSubstitute(s);
        a.setAdjustKind("本次暂缓原品换药·到货补做");
        a.setOriginalPlanSnapshot(s.getOriginalUsageSnapshot());
        a.setAdjustmentContent("本次不使用替代材料，按现场条件行伤口清洁消毒+无菌敷料临时覆盖；"
                + "原品[" + s.getOriginalMaterial().getName() + "]补货到货后 24 小时内安排上门补做，"
                + "期间家属观察渗液/红肿/发热，异常立即联系责任护士或家庭医生");
        a.setExpectedMakeupAt(LocalDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0));
        a.setStatus(CareAdjustStatus.PROPOSED);
        return adjustmentRepository.save(a);
    }

    // ===== 替代执行 =====

    @Transactional
    public MaterialSubstitute execute(Long id, ExecuteCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅护士可执行替代材料领用");
        }
        MaterialSubstitute s = get(id);
        if (s.getStatus() != SubstituteStatus.FAMILY_AGREED) {
            throw new BusinessException("须医生确认且家属签字同意后方可执行，当前：" + s.getStatus().label);
        }
        Material m = s.getSubstituteMaterial();
        int qty = s.getQuantity() == null ? 1 : s.getQuantity();
        if (m.getStockQty() == null || m.getStockQty() < qty) {
            throw new BusinessException("替代品[" + m.getName() + "]库存不足，无法执行");
        }
        m.setStockQty(m.getStockQty() - qty);
        materialRepository.save(m);

        MaterialTransaction tx = new MaterialTransaction();
        tx.setMaterial(m);
        tx.setType(MaterialTxType.OUT);
        tx.setQuantity(qty);
        tx.setVisit(s.getVisit());
        tx.setOperator(userRepository.findById(me.id()).orElse(null));
        tx.setBatchNo(cmd == null ? null : cmd.batchNo());
        tx.setSubstitute(s);
        tx.setNote("缺货替代执行：" + s.getSubstituteNo() + "，原品[" + s.getOriginalMaterial().getName()
                + "]缺货，经医生确认（" + safeName(s.getDoctor()) + "）及家属签字同意（"
                + s.getFamilySignName() + "）后替代使用"
                + (cmd == null || cmd.note() == null ? "" : "；" + cmd.note()));
        tx.setOccurredAt(LocalDateTime.now());
        tx = txRepository.save(tx);

        s.setExecutedTx(tx);
        s.setBatchNo(cmd == null ? null : cmd.batchNo());
        s.setExecutionNote(cmd == null ? null : cmd.note());
        s.setExecutedBy(userRepository.findById(me.id()).orElse(null));
        s.setExecutedAt(LocalDateTime.now());
        s.setStatus(SubstituteStatus.EXECUTED);
        s = substituteRepository.save(s);

        // 执行时的伤口/用药照片同时作为首次追踪证据
        if (cmd != null && cmd.photoPaths() != null) {
            for (String path : cmd.photoPaths()) {
                saveTrackingPhoto(s, s.getVisit(), path, "替代执行当日伤口/用药照片");
            }
        }
        auditService.log(me, "耗材替代", "替代材料执行出库", s.getId(), s.getSubstituteNo(),
                s.getPatient().getId(), m.getName() + " 出库 " + qty + m.getUnit()
                        + "；流水#" + tx.getId());
        return s;
    }

    // ===== 后续伤口效果追踪 =====

    @Transactional
    public SubstituteTracking addTracking(Long id, TrackingCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅护士/医生可登记效果追踪");
        }
        MaterialSubstitute s = get(id);
        if (s.getStatus() != SubstituteStatus.EXECUTED && s.getStatus() != SubstituteStatus.DOCTOR_REVIEWED) {
            throw new BusinessException("仅已执行的替代单可追踪效果");
        }
        SubstituteFollowUp outcome = SubstituteFollowUp.valueOf(cmd.outcome());
        Visit visit = cmd.visitId() == null ? s.getVisit()
                : visitRepository.findById(cmd.visitId()).orElse(s.getVisit());

        SubstituteTracking t = new SubstituteTracking();
        t.setSubstitute(s);
        t.setVisit(visit);
        t.setOutcome(outcome);
        t.setNote(cmd.note());
        t.setRecorder(userRepository.findById(me.id()).orElse(null));
        t.setRecordedAt(LocalDateTime.now());
        int photoCount = 0;
        if (cmd.photoPaths() != null) {
            for (String path : cmd.photoPaths()) {
                saveTrackingPhoto(s, visit, path, outcome.label + "追踪伤口照片");
                photoCount++;
            }
        }
        t.setPhotoCount(photoCount);
        t = trackingRepository.save(t);

        s.setLatestOutcome(outcome);
        s.setLatestOutcomeAt(t.getRecordedAt());
        substituteRepository.save(s);

        // 愈合变慢 / 过敏 → 自动风险事件，提示医生回看替代原因
        if (outcome != SubstituteFollowUp.NORMAL) {
            RiskEvent e = new RiskEvent();
            e.setRiskNo(ApplicationService.generateNo("FX"));
            e.setPatient(s.getPatient());
            e.setVisit(visit);
            e.setRiskType(outcome == SubstituteFollowUp.ALLERGY ? "替代材料过敏反应" : "替代后伤口愈合变慢");
            e.setSeverity(outcome == SubstituteFollowUp.ALLERGY ? RiskSeverity.HIGH : RiskSeverity.MEDIUM);
            e.setDescription("使用替代材料[" + s.getSubstituteMaterial().getName()
                    + "]（替代原品 " + s.getOriginalMaterial().getName() + "，确认单 " + s.getSubstituteNo()
                    + "）后随访提示：" + outcome.label + "。" + (cmd.note() == null ? "" : cmd.note()));
            e.setStatus(RiskEventStatus.OPEN);
            e.setReporter(userRepository.findById(me.id()).orElse(null));
            e.setReportedAt(t.getRecordedAt());
            riskRepository.save(e);
        }
        auditService.log(me, "耗材替代", "效果追踪·" + outcome.label, s.getId(), s.getSubstituteNo(),
                s.getPatient().getId(), cmd.note() + "；照片 " + photoCount + " 张");
        return t;
    }

    // ===== 医生回看替代原因并调整后续护理材料 =====

    @Transactional
    public MaterialSubstitute doctorReview(Long id, ReviewCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅医生可回看并调整后续护理材料");
        }
        MaterialSubstitute s = get(id);
        if (s.getStatus() != SubstituteStatus.EXECUTED) {
            throw new BusinessException("仅执行中的替代单可回看调整，当前：" + s.getStatus().label);
        }
        s.setReviewDoctor(userRepository.findById(me.id()).orElse(null));
        s.setReviewComment(cmd.comment());
        s.setReviewAction(cmd.action());
        s.setReviewedAt(LocalDateTime.now());

        MedicalOrder order = null;
        if (cmd.createOrder() && cmd.action() != null && !cmd.action().isBlank()) {
            order = new MedicalOrder();
            order.setOrderNo(ApplicationService.generateNo("YZ"));
            order.setPatient(s.getPatient());
            order.setVisit(s.getVisit());
            order.setPlan(s.getPlan());
            order.setDoctor(userRepository.findById(me.id()).orElse(null));
            order.setOrderType(OrderType.CARE_PLAN_CHANGE);
            order.setContent("后续护理材料调整：" + cmd.action());
            order.setDiagnosisBasis("回看缺货替代确认单 " + s.getSubstituteNo()
                    + "（原品 " + s.getOriginalMaterial().getName() + " → 替代 "
                    + s.getSubstituteMaterial().getName() + "），近期随访效果："
                    + (s.getLatestOutcome() == null ? "未登记" : s.getLatestOutcome().label)
                    + "。医生回看意见：" + cmd.comment());
            order.setStatus(OrderStatus.PROPOSED);
            order = orderRepository.save(order);
            s.setReviewOrder(order);
        }
        s.setStatus(SubstituteStatus.DOCTOR_REVIEWED);
        s = substituteRepository.save(s);
        auditService.log(me, "耗材替代", "医生回看替代原因并调整护理材料", s.getId(), s.getSubstituteNo(),
                s.getPatient().getId(), "回看意见：" + cmd.comment() + "；调整措施：" + cmd.action()
                        + (order != null ? "；已下护理方案医嘱 " + order.getOrderNo() : ""));
        return s;
    }

    // ===== 补货任务 =====

    @Transactional(readOnly = true)
    public List<MaterialReplenishTask> replenishList(String status) {
        List<MaterialReplenishTask> list;
        if (status != null && !status.isBlank()) {
            list = replenishRepository.findByStatusOrderByCreatedAtDesc(ReplenishStatus.valueOf(status));
        } else {
            list = replenishRepository.findAllByOrderByCreatedAtDesc();
        }
        list.forEach(t -> {
            org.hibernate.Hibernate.initialize(t.getMaterial());
            if (t.getPatient() != null) org.hibernate.Hibernate.initialize(t.getPatient());
            if (t.getCreator() != null) org.hibernate.Hibernate.initialize(t.getCreator());
            if (t.getOrderedBy() != null) org.hibernate.Hibernate.initialize(t.getOrderedBy());
            if (t.getArrivalTx() != null) org.hibernate.Hibernate.initialize(t.getArrivalTx());
        });
        return list;
    }

    /** 管理者/护士也可基于缺货预警直接建补货任务 */
    @Transactional
    public MaterialReplenishTask createReplenish(Long materialId, Integer requiredQty,
                                                 String reason, Long patientId) {
        LoginUser me = SecurityUtils.current();
        Material m = materialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(404, "耗材不存在"));
        Patient p = patientId == null ? null
                : patientRepository.findById(patientId).orElse(null);
        MaterialReplenishTask t = new MaterialReplenishTask();
        t.setTaskNo(ApplicationService.generateNo("BH"));
        t.setMaterial(m);
        t.setRequiredQty(requiredQty == null || requiredQty <= 0
                ? Math.max(10, m.getSafetyStock() == null ? 10 : m.getSafetyStock()) : requiredQty);
        t.setReason(reason);
        t.setPatient(p);
        t.setStatus(ReplenishStatus.PENDING);
        t.setCreator(userRepository.findById(me.id()).orElse(null));
        t = replenishRepository.save(t);
        auditService.log(me, "耗材补货", "新建补货任务", t.getId(), t.getTaskNo(),
                p == null ? null : p.getId(),
                m.getName() + " x" + t.getRequiredQty() + "；" + reason);
        return t;
    }

    @Transactional
    public MaterialReplenishTask orderReplenish(Long id, String note, LocalDateTime expectedArrivalAt) {
        LoginUser me = SecurityUtils.current();
        MaterialReplenishTask t = mustTask(id);
        if (t.getStatus() != ReplenishStatus.PENDING && t.getStatus() != ReplenishStatus.ORDERED) {
            throw new BusinessException("当前状态不可下单：" + t.getStatus().label);
        }
        t.setStatus(ReplenishStatus.ORDERED);
        t.setOrderNote(note);
        t.setOrderedBy(userRepository.findById(me.id()).orElse(null));
        t.setOrderedAt(LocalDateTime.now());
        t.setExpectedArrivalAt(expectedArrivalAt);
        t = replenishRepository.save(t);
        auditService.log(me, "耗材补货", "提交采购/催货", t.getId(), t.getTaskNo(),
                t.getPatient() == null ? null : t.getPatient().getId(),
                t.getMaterial().getName() + " x" + t.getRequiredQty() + "；" + note);
        return t;
    }

    public record ArriveCommand(Integer quantity, String batchNo, java.time.LocalDate expiryDate,
                                Double coldChainTemp, String note) {}

    @Transactional
    public MaterialReplenishTask arriveReplenish(Long id, ArriveCommand cmd) {
        LoginUser me = SecurityUtils.current();
        MaterialReplenishTask t = mustTask(id);
        if (t.getStatus() == ReplenishStatus.ARRIVED || t.getStatus() == ReplenishStatus.CLOSED) {
            throw new BusinessException("补货任务已到货，请勿重复入库");
        }
        int qty = cmd.quantity() == null || cmd.quantity() <= 0 ? t.getRequiredQty() : cmd.quantity();
        Material m = t.getMaterial();
        m.setStockQty((m.getStockQty() == null ? 0 : m.getStockQty()) + qty);
        if (cmd.expiryDate() != null) m.setNearestExpiry(cmd.expiryDate());
        materialRepository.save(m);

        MaterialTransaction tx = new MaterialTransaction();
        tx.setMaterial(m);
        tx.setType(MaterialTxType.IN);
        tx.setQuantity(qty);
        tx.setBatchNo(cmd.batchNo());
        tx.setExpiryDate(cmd.expiryDate());
        tx.setColdChainTemp(cmd.coldChainTemp());
        tx.setNote("补货任务 " + t.getTaskNo() + " 到货入库"
                + (cmd.note() == null ? "" : "；" + cmd.note()));
        tx.setOccurredAt(LocalDateTime.now());
        tx = txRepository.save(tx);

        t.setStatus(ReplenishStatus.ARRIVED);
        t.setArrivalTx(tx);
        t.setArrivedAt(LocalDateTime.now());
        t = replenishRepository.save(t);
        auditService.log(me, "耗材补货", "到货入库", t.getId(), t.getTaskNo(),
                t.getPatient() == null ? null : t.getPatient().getId(),
                m.getName() + " 入库 " + qty + m.getUnit() + "，当前库存 " + m.getStockQty());
        return t;
    }

    // ===== 护理计划调整（补做） =====

    @Transactional(readOnly = true)
    public List<CarePlanAdjustment> adjustmentList(String status) {
        List<CarePlanAdjustment> list;
        if (status != null && !status.isBlank()) {
            list = adjustmentRepository.findByStatusOrderByCreatedAtDesc(CareAdjustStatus.valueOf(status));
        } else {
            list = adjustmentRepository.findAllByOrderByCreatedAtDesc();
        }
        list.forEach(a -> {
            org.hibernate.Hibernate.initialize(a.getPatient());
            if (a.getVisit() != null) org.hibernate.Hibernate.initialize(a.getVisit());
            if (a.getPlan() != null) org.hibernate.Hibernate.initialize(a.getPlan());
            if (a.getMaker() != null) org.hibernate.Hibernate.initialize(a.getMaker());
            if (a.getMakeupVisit() != null) org.hibernate.Hibernate.initialize(a.getMakeupVisit());
        });
        return list;
    }

    @Transactional
    public CarePlanAdjustment scheduleMakeup(Long id, LocalDateTime expectedMakeupAt, String content) {
        LoginUser me = SecurityUtils.current();
        CarePlanAdjustment a = mustAdjustment(id);
        if (expectedMakeupAt != null) a.setExpectedMakeupAt(expectedMakeupAt);
        if (content != null && !content.isBlank()) a.setAdjustmentContent(content);
        a = adjustmentRepository.save(a);
        auditService.log(me, "护理计划调整", "安排补做", a.getId(), a.getAdjustNo(),
                a.getPatient().getId(), "计划补做时间：" + a.getExpectedMakeupAt());
        return a;
    }

    @Transactional
    public CarePlanAdjustment completeAdjustment(Long id, Long makeupVisitId, String note) {
        LoginUser me = SecurityUtils.current();
        CarePlanAdjustment a = mustAdjustment(id);
        if (a.getStatus() != CareAdjustStatus.PROPOSED) {
            throw new BusinessException("调整已完成，无需重复登记");
        }
        Visit makeup = makeupVisitId == null ? null
                : visitRepository.findById(makeupVisitId).orElse(null);
        a.setMakeupVisit(makeup);
        a.setStatus(CareAdjustStatus.DONE);
        a.setMaker(userRepository.findById(me.id()).orElse(null));
        a.setCompletionNote(note);
        a.setCompletedAt(LocalDateTime.now());
        a = adjustmentRepository.save(a);

        // 联动关闭补货任务（原品已用上）
        MaterialSubstitute s = a.getSubstitute();
        if (s != null && s.getReplenishTask() != null
                && s.getReplenishTask().getStatus() == ReplenishStatus.ARRIVED) {
            MaterialReplenishTask t = s.getReplenishTask();
            t.setStatus(ReplenishStatus.CLOSED);
            t.setCloser(userRepository.findById(me.id()).orElse(null));
            t.setCloseNote("原品已于补做巡诊使用，补货任务闭环");
            replenishRepository.save(t);
        }
        auditService.log(me, "护理计划调整", "补做完成", a.getId(), a.getAdjustNo(),
                a.getPatient().getId(), "补做巡诊："
                        + (makeup == null ? "-" : makeup.getVisitNo()) + "；说明：" + note);
        return a;
    }

    // ===== 私有辅助 =====

    private MaterialSubstitute get(Long id) {
        return substituteRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "替代确认单不存在"));
    }

    private MaterialReplenishTask mustTask(Long id) {
        return replenishRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "补货任务不存在"));
    }

    private CarePlanAdjustment mustAdjustment(Long id) {
        return adjustmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "护理计划调整记录不存在"));
    }

    private void saveTrackingPhoto(MaterialSubstitute s, Visit visit, String path, String caption) {
        VisitPhoto p = new VisitPhoto();
        p.setVisit(visit);
        p.setSubstitute(s);
        p.setFilePath(path);
        p.setPhotoType("substitute_tracking");
        p.setCaption(caption);
        photoRepository.save(p);
    }

    private BedApplication latestApprovedApp(Long patientId) {
        return applicationRepository.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .filter(a -> a.getStatus() == BedApplyStatus.APPROVED
                        && Boolean.TRUE.equals(a.getInsuranceEligible()))
                .findFirst().orElse(null);
    }

    private record CostSnap(BigDecimal price, boolean reimbursable, String insCat, BigDecimal rate,
                            BigDecimal total, BigDecimal ins, BigDecimal self) {}

    /** 与 BillingService.applySplit 保持同一报销拆分口径 */
    private CostSnap costSnap(Material m, int qty, Patient patient, BedApplication app) {
        ChargeCatalog cat = m.getCatalog() == null ? null
                : m.getCatalog().getId() == null ? null : m.getCatalog();
        BigDecimal price = cat != null ? cat.getUnitPrice()
                : (m.getUnitCost() != null ? m.getUnitCost() : BigDecimal.ZERO);
        BigDecimal total = money(price.multiply(BigDecimal.valueOf(qty)));
        boolean reimbursable = cat != null && Boolean.TRUE.equals(cat.getReimbursable());
        String insCat = cat != null && cat.getInsuranceCategory() != null ? cat.getInsuranceCategory() : "丙类";
        BigDecimal rate = cat != null && cat.getReimbursementRate() != null
                ? cat.getReimbursementRate() : BigDecimal.ZERO;
        boolean eligible = app != null && patient.getInsuranceType() != InsuranceType.NONE && reimbursable;
        BigDecimal ins = eligible ? money(total.multiply(rate)) : BigDecimal.ZERO;
        BigDecimal self = money(total.subtract(ins));
        return new CostSnap(price, reimbursable, insCat, rate, total, ins, self);
    }

    private BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 从材料名称提取用于匹配医嘱内容的关键词（敷料/换药包/纱布等），名称过短则返回 null */
    private String materialKeyword(Material m) {
        String name = m.getName() == null ? "" : m.getName();
        for (String kw : List.of("敷料", "换药包", "纱布", "导尿包", "敷贴", "胰岛素", "胶带", "棉球")) {
            if (name.contains(kw)) return kw;
        }
        String bare = name.replaceAll("（.*?）|\\(.*?\\)", "").trim();
        return bare.length() >= 2 ? bare.substring(0, 2) : null;
    }

    private String safeName(SysUser u) { return u == null ? "-" : u.getName(); }
}
