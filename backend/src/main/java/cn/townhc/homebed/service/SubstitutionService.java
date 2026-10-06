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
 * 耗材缺货替代确认。
 * 护士发起 → 系统展示原医嘱/替代规格/医保报销差异/可能影响 → 医生确认
 * → 家属知情手写签字：同意则按替代材料执行（扣库存、出库留痕）；
 *   不同意则自动生成加急补货任务、登记缺货异常并调整本次护理计划。
 * 执行后在后续上门伤口照片中持续随访；愈合变慢/过敏自动生成风险事件，
 * 医生可回看替代原因并下达后续护理材料调整医嘱。
 */
@Service
@RequiredArgsConstructor
public class SubstitutionService {

    private final MaterialSubstitutionRepository substitutionRepository;
    private final WoundFollowUpRepository followUpRepository;
    private final MaterialRepository materialRepository;
    private final ChargeCatalogRepository catalogRepository;
    private final VisitRepository visitRepository;
    private final MedicalOrderRepository orderRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final RiskEventRepository riskRepository;
    private final BedApplicationRepository applicationRepository;
    private final SysUserRepository userRepository;
    private final MaterialTransactionRepository txRepository;
    private final RestockService restockService;
    private final AuditService auditService;

    public record ProposeCommand(Long visitId, Long originalMaterialId, Long substituteMaterialId,
                                 Long medicalOrderId, Integer quantity, String originalOrderSnapshot,
                                 String shortageReason, String possibleImpact) {}

    public record FollowUpCommand(Long substitutionId, Long visitId, List<String> photoPaths,
                                  WoundOutcome outcome, Double woundArea, String observation,
                                  String nurseNote) {}

    @Transactional(readOnly = true)
    public List<MaterialSubstitution> list(String status, Long patientId, Long visitId) {
        LoginUser me = SecurityUtils.current();
        List<MaterialSubstitution> list;
        if (visitId != null) {
            list = substitutionRepository.findByVisitId(visitId);
        } else if (me.role() == Role.FAMILY) {
            list = substitutionRepository.findAll().stream()
                    .filter(s -> s.getPatient().getFamilyUser() != null
                            && s.getPatient().getFamilyUser().getId().equals(me.id()))
                    .toList();
        } else if (patientId != null) {
            list = substitutionRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        } else if (status != null && !status.isBlank()) {
            list = substitutionRepository.findByStatusOrderByCreatedAtDesc(SubstituteStatus.valueOf(status));
        } else {
            list = substitutionRepository.findAll();
        }
        List<MaterialSubstitution> filtered = new ArrayList<>(list);
        if (visitId == null && status != null && !status.isBlank()) {
            SubstituteStatus st = SubstituteStatus.valueOf(status);
            filtered.removeIf(s -> s.getStatus() != st);
        }
        filtered.sort(Comparator.comparing(MaterialSubstitution::getCreatedAt).reversed());
        filtered.forEach(this::initForView);
        return filtered;
    }

    /** 在事务内初始化展示所需的懒加载关联（否则 Jackson 仅输出 {id}） */
    private void initForView(MaterialSubstitution s) {
        s.getOriginalMaterial().getName();
        s.getOriginalMaterial().getSpec();
        s.getSubstituteMaterial().getName();
        s.getSubstituteMaterial().getSpec();
        s.getPatient().getName();
        s.getVisit().getVisitNo();
        if (s.getProposer() != null) s.getProposer().getName();
        if (s.getDoctor() != null) s.getDoctor().getName();
        if (s.getOutTransaction() != null) s.getOutTransaction().getId();
        if (s.getRestockTask() != null) {
            s.getRestockTask().getTaskNo();
            s.getRestockTask().getStatus();
            s.getRestockTask().getMaterial().getName();
        }
        if (s.getAdjustOrder() != null) {
            s.getAdjustOrder().getOrderNo();
            s.getAdjustOrder().getOrderType();
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        MaterialSubstitution s = get(id);
        LoginUser me = SecurityUtils.current();
        if (me.role() == Role.FAMILY && (s.getPatient().getFamilyUser() == null
                || !s.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能查看本人患者的替代确认单");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        initForView(s);
        map.put("substitution", s);
        List<WoundFollowUp> followUps = followUpRepository.findBySubstitutionIdOrderByObservedAtAsc(id);
        followUps.forEach(f -> {
            f.getPatient().getName();
            if (f.getVisit() != null) f.getVisit().getVisitNo();
            if (f.getRecorder() != null) f.getRecorder().getName();
        });
        map.put("followUps", followUps);
        List<RiskEvent> risks = riskRepository.findAll().stream()
                .filter(r -> r.getSubstitution() != null && r.getSubstitution().getId().equals(id))
                .sorted(Comparator.comparing(RiskEvent::getReportedAt).reversed())
                .toList();
        risks.forEach(r -> {
            r.getPatient().getName();
            if (r.getReporter() != null) r.getReporter().getName();
            if (r.getVisit() != null) r.getVisit().getVisitNo();
            initForView(r.getSubstitution());
        });
        map.put("relatedRisks", risks);
        return map;
    }

    private MaterialSubstitution get(Long id) {
        return substitutionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "替代确认单不存在"));
    }

    /** 替代候选：有库存的同类（CONSUMABLE）耗材 + 医保报销差异测算，供护士发起时选择 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(Long visitId, Long originalMaterialId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new BusinessException(404, "巡诊任务不存在"));
        Material original = materialRepository.findById(originalMaterialId)
                .orElseThrow(() -> new BusinessException(404, "原医嘱耗材不存在"));
        BedApplication app = visit.getPlan().getApplication();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Material m : materialRepository.findByActiveTrueOrderByCodeAsc()) {
            if (m.getId().equals(original.getId())) continue;
            if (m.getStockQty() == null || m.getStockQty() <= 0) continue; // 替代候选必须有货
            if (m.getCategory() != original.getCategory()
                    && m.getCategory() != CatalogCategory.CONSUMABLE) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("material", m);
            row.putAll(diff(original, m, 1, app));
            row.put("impactTemplate", buildImpact(original, m));
            result.add(row);
        }
        result.sort((a, b) -> {
            // 同品类优先；敷料类原品优先展示“敷料/纱/油纱”等换药物料
            Material ma = (Material) a.get("material");
            Material mb = (Material) b.get("material");
            boolean ka = dressingLike(ma.getName()), kb = dressingLike(mb.getName());
            if (dressingLike(original.getName()) && ka != kb) return ka ? -1 : 1;
            if (Objects.equals(ma.getCategory(), original.getCategory())
                    != Objects.equals(mb.getCategory(), original.getCategory())) {
                return Objects.equals(ma.getCategory(), original.getCategory()) ? -1 : 1;
            }
            return ((BigDecimal) a.get("selfPayDiff")).compareTo((BigDecimal) b.get("selfPayDiff"));
        });
        return result;
    }

    /** 是否为伤口换药物料（敷料/纱布/油纱/敷贴等），用于候选相关性排序 */
    private boolean dressingLike(String name) {
        if (name == null) return false;
        return name.contains("敷料") || name.contains("纱布") || name.contains("油纱")
                || name.contains("敷贴") || name.contains("敷");
    }

    @Transactional
    public MaterialSubstitution propose(ProposeCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅上门护士可发起耗材替代");
        }
        Visit visit = visitRepository.findById(cmd.visitId())
                .orElseThrow(() -> new BusinessException(404, "巡诊任务不存在"));
        Material original = materialRepository.findById(cmd.originalMaterialId())
                .orElseThrow(() -> new BusinessException(404, "原医嘱耗材不存在"));
        Material substitute = materialRepository.findById(cmd.substituteMaterialId())
                .orElseThrow(() -> new BusinessException(404, "替代耗材不存在"));
        int qty = cmd.quantity() == null || cmd.quantity() <= 0 ? 1 : cmd.quantity();
        if (original.getStockQty() != null && original.getStockQty() >= qty) {
            throw new BusinessException("原品[" + original.getName() + "]当前库存 "
                    + original.getStockQty() + original.getUnit() + " 仍可满足，无需发起替代");
        }
        if (substitute.getStockQty() == null || substitute.getStockQty() < qty) {
            throw new BusinessException("替代品[" + substitute.getName() + "]库存不足，无法作为替代材料");
        }
        // 同一巡诊同一原品不得存在未终结的替代单
        for (MaterialSubstitution exist : substitutionRepository.findByVisitId(visit.getId())) {
            if (exist.getOriginalMaterial().getId().equals(original.getId())
                    && (exist.getStatus() == SubstituteStatus.PROPOSED
                    || exist.getStatus() == SubstituteStatus.DOCTOR_CONFIRMED)) {
                throw new BusinessException("该巡诊对原品[" + original.getName()
                        + "]已存在进行中的替代确认单 " + exist.getSubstitutionNo());
            }
        }
        MedicalOrder order = cmd.medicalOrderId() == null ? null
                : orderRepository.findById(cmd.medicalOrderId()).orElse(null);

        MaterialSubstitution s = new MaterialSubstitution();
        s.setSubstitutionNo(ApplicationService.generateNo("TD"));
        s.setVisit(visit);
        s.setPatient(visit.getPatient());
        s.setPlan(visit.getPlan());
        s.setProposer(userRepository.findById(me.id()).orElse(null));
        s.setOriginalMaterial(original);
        s.setSubstituteMaterial(substitute);
        s.setMedicalOrder(order);
        s.setQuantity(qty);
        String snapshot = cmd.originalOrderSnapshot();
        if ((snapshot == null || snapshot.isBlank()) && order != null) {
            snapshot = order.getOrderType().label + "：" + order.getContent();
        }
        s.setOriginalOrderSnapshot(snapshot == null ? "医嘱指定使用：" + original.getName()
                + (original.getSpec() == null ? "" : "（" + original.getSpec() + "）") : snapshot);
        s.setShortageReason(cmd.shortageReason() == null || cmd.shortageReason().isBlank()
                ? "卫生院库存不足，当前库存 " + nz(original.getStockQty()) + original.getUnit()
                : cmd.shortageReason());
        s.setPossibleImpact((cmd.possibleImpact() == null || cmd.possibleImpact().isBlank())
                ? buildImpact(original, substitute) : cmd.possibleImpact());

        Map<String, Object> d = diff(original, substitute, qty, visit.getPlan().getApplication());
        applyDiff(s, d);
        s.setStatus(SubstituteStatus.PROPOSED);
        s = substitutionRepository.save(s);
        auditService.log(me, "耗材替代", "发起替代申请", s.getId(), s.getSubstitutionNo(),
                visit.getPatient().getId(),
                "原品 " + original.getName() + "(库存" + nz(original.getStockQty()) + ") → 替代品 "
                        + substitute.getName() + " x" + qty
                        + "；自费差异 " + s.getSelfPayDiff()
                        + "；报销差异 " + s.getInsuranceDiff());
        return s;
    }

    /** 医生确认替代方案（可退回，退回后本单取消，护士需重新发起） */
    @Transactional
    public MaterialSubstitution doctorConfirm(Long id, boolean approved, String comment) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可确认替代方案");
        }
        MaterialSubstitution s = get(id);
        if (s.getStatus() != SubstituteStatus.PROPOSED) {
            throw new BusinessException("仅待医生确认的替代单可操作，当前：" + s.getStatus().label);
        }
        s.setDoctor(userRepository.findById(me.id()).orElse(null));
        s.setDoctorComment(comment);
        s.setDoctorConfirmedAt(LocalDateTime.now());
        s.setStatus(approved ? SubstituteStatus.DOCTOR_CONFIRMED : SubstituteStatus.CANCELLED);
        s = substitutionRepository.save(s);
        auditService.log(me, "耗材替代", approved ? "医生确认替代" : "医生退回替代",
                s.getId(), s.getSubstitutionNo(), s.getPatient().getId(),
                (approved ? "同意使用替代品：" : "不同意替代，退回：") + comment);
        return s;
    }

    /**
     * 家属知情决定（现场由家属确认并手写签名）。
     * 同意 → 执行：扣替代库存、生成标注替代来源的出库流水；
     * 不同意 → 加急补货任务 + 登记缺货异常 + 调整本次护理计划。
     */
    @Transactional
    public MaterialSubstitution familyDecide(Long id, boolean agreed, String signName, String signPath,
                                             String comment, String planAdjustment) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.FAMILY && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "无权操作家属确认");
        }
        MaterialSubstitution s = get(id);
        if (me.role() == Role.FAMILY && (s.getPatient().getFamilyUser() == null
                || !s.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能对本人患者的替代单作家属确认");
        }
        if (s.getStatus() != SubstituteStatus.DOCTOR_CONFIRMED) {
            throw new BusinessException("医生确认后家属方可签字，当前：" + s.getStatus().label);
        }
        s.setFamilyComment(comment);
        if (me.role() == Role.FAMILY) {
            signName = me.name();
        }
        if (agreed) {
            if (signName == null || signName.isBlank() || signPath == null || signPath.isBlank()) {
                throw new BusinessException("家属同意替代必须留存手写签字与签字人姓名");
            }
            Material sub = s.getSubstituteMaterial();
            int qty = s.getQuantity() == null ? 1 : s.getQuantity();
            if (sub.getStockQty() == null || sub.getStockQty() < qty) {
                throw new BusinessException("替代品[" + sub.getName() + "]库存已不足，请核实后重新发起");
            }
            // 扣库存
            sub.setStockQty(sub.getStockQty() - qty);
            materialRepository.save(sub);
            // 替代出库流水（账单与追溯据此识别）
            MaterialTransaction tx = new MaterialTransaction();
            tx.setMaterial(sub);
            tx.setType(MaterialTxType.OUT);
            tx.setQuantity(qty);
            tx.setVisit(s.getVisit());
            tx.setOperator(userRepository.findById(me.id()).orElse(null));
            tx.setNote("替代出库：替代单 " + s.getSubstitutionNo() + "，原医嘱指定["
                    + s.getOriginalMaterial().getName() + "]缺货，经医生确认、家属签字后使用["
                    + sub.getName() + (sub.getSpec() == null ? "" : " " + sub.getSpec()) + "]");
            tx.setOccurredAt(LocalDateTime.now());
            tx = txRepository.save(tx);

            s.setOutTransaction(tx);
            s.setFamilySignName(signName);
            s.setFamilySignPath(signPath);
            s.setFamilySignedAt(LocalDateTime.now());
            s.setExecutedAt(LocalDateTime.now());
            s.setStatus(SubstituteStatus.EXECUTED);
            s = substitutionRepository.save(s);
            auditService.log(me, "耗材替代", "家属签字同意并执行", s.getId(), s.getSubstitutionNo(),
                    s.getPatient().getId(),
                    "签字人：" + signName + "；替代品 " + sub.getName() + " x" + qty
                            + "；家属意见：" + nz(comment));
        } else {
            if (planAdjustment == null || planAdjustment.isBlank()) {
                throw new BusinessException("家属不同意替代时，必须填写本次护理计划调整方案");
            }
            s.setStatus(SubstituteStatus.REJECTED_BY_FAMILY);
            s.setFamilySignedAt(LocalDateTime.now());
            s.setPlanAdjustment(planAdjustment);
            // 1) 自动生成加急补货任务（RestockService 负责闭环）
            RestockTask task = restockService.createForSubstitution(s);
            s.setRestockTask(task);
            s = substitutionRepository.save(s);
            // 2) 登记缺货异常（记录留痕，不强制挂起——本次护理按调整方案执行）
            VisitException ex = new VisitException();
            ex.setVisit(s.getVisit());
            ex.setPatient(s.getPatient());
            ex.setType(ExceptionType.SUPPLY_SHORTAGE);
            ex.setDescription("医嘱指定[" + s.getOriginalMaterial().getName() + "]库存不足，护士提出替代品["
                    + s.getSubstituteMaterial().getName() + "]，医生已确认但家属不同意。"
                    + "本次护理调整：" + planAdjustment + "；已自动生成加急补货任务 " + task.getTaskNo()
                    + "，到货后安排原品补换药。家属意见：" + nz(comment));
            ex.setStatus(ExceptionStatus.REPORTED);
            exceptionRepository.save(ex);
            // 3) 巡诊安排说明追加调整，便于护士执行时看到
            Visit v = s.getVisit();
            v.setArrangementNote((v.getArrangementNote() == null ? "" : v.getArrangementNote() + "；")
                    + "【替代被拒】" + s.getOriginalMaterial().getName() + " 暂不使用替代品，本次按调整方案护理："
                    + planAdjustment + "，待补货任务 " + task.getTaskNo() + " 到货后补换");
            visitRepository.save(v);
            auditService.log(me, "耗材替代", "家属不同意替代", s.getId(), s.getSubstitutionNo(),
                    s.getPatient().getId(),
                    "补货任务 " + task.getTaskNo() + "；本次护理调整：" + planAdjustment
                            + "；家属意见：" + nz(comment));
        }
        return s;
    }

    /** 后续上门：依据伤口照片记录替代材料效果 */
    @Transactional
    public WoundFollowUp recordFollowUp(FollowUpCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅护士/医生可记录伤口随访");
        }
        MaterialSubstitution s = get(cmd.substitutionId());
        if (s.getStatus() != SubstituteStatus.EXECUTED) {
            throw new BusinessException("仅已执行的替代单需要效果随访，当前：" + s.getStatus().label);
        }
        if (cmd.outcome() == null) throw new BusinessException("请选择本次伤口观察结论");
        Visit visit = cmd.visitId() == null ? null
                : visitRepository.findById(cmd.visitId()).orElse(null);
        WoundFollowUp f = new WoundFollowUp();
        f.setSubstitution(s);
        f.setPatient(s.getPatient());
        f.setVisit(visit);
        f.setPhotoPaths(cmd.photoPaths() == null ? null : String.join(",", cmd.photoPaths()));
        f.setOutcome(cmd.outcome());
        f.setWoundArea(cmd.woundArea());
        f.setObservation(cmd.observation());
        f.setNurseNote(cmd.nurseNote());
        f.setRecorder(userRepository.findById(me.id()).orElse(null));
        f.setObservedAt(LocalDateTime.now());
        f = followUpRepository.save(f);

        // 异常结论自动生成风险事件，并回挂替代单，供医生回看替代原因
        if (cmd.outcome() == WoundOutcome.ALLERGY || cmd.outcome() == WoundOutcome.INFECTION
                || cmd.outcome() == WoundOutcome.SLOWER) {
            RiskSeverity sev = cmd.outcome() == WoundOutcome.SLOWER ? RiskSeverity.MEDIUM : RiskSeverity.HIGH;
            String type = switch (cmd.outcome()) {
                case ALLERGY -> "替代耗材过敏";
                case INFECTION -> "伤口感染征象";
                default -> "伤口愈合变慢";
            };
            RiskEvent risk = new RiskEvent();
            risk.setRiskNo(ApplicationService.generateNo("FX"));
            risk.setPatient(s.getPatient());
            risk.setVisit(visit);
            risk.setSubstitution(s);
            risk.setRiskType(type);
            risk.setSeverity(sev);
            risk.setDescription("替代材料[" + s.getSubstituteMaterial().getName()
                    + "]（替代单 " + s.getSubstitutionNo() + "，替代原品["
                    + s.getOriginalMaterial().getName() + "]）随访结论：" + cmd.outcome().label
                    + "。观察：" + nz(cmd.observation())
                    + (cmd.woundArea() != null ? "；创面约 " + cmd.woundArea() + "cm²" : "")
                    + "。请医生回看替代原因与家属知情记录，评估停用/更换护理材料。");
            risk.setStatus(RiskEventStatus.OPEN);
            risk.setReporter(userRepository.findById(me.id()).orElse(null));
            risk.setReportedAt(LocalDateTime.now());
            if (cmd.photoPaths() != null && !cmd.photoPaths().isEmpty()) {
                risk.setEvidencePath(cmd.photoPaths().get(0));
            }
            riskRepository.save(risk);
            auditService.log(me, "替代效果随访", type + "自动风险", f.getId(), s.getSubstitutionNo(),
                    s.getPatient().getId(), cmd.outcome().label + "；" + nz(cmd.observation()));
        }
        auditService.log(me, "替代效果随访", "记录伤口随访", f.getId(), s.getSubstitutionNo(),
                s.getPatient().getId(),
                cmd.outcome().label + (visit != null ? "；巡诊 " + visit.getVisitNo() : ""));
        return f;
    }

    /** 医生回看替代原因后，调整后续护理材料（同步生成“调整护理方案”医嘱） */
    @Transactional
    public MaterialSubstitution doctorAdjustMaterial(Long id, String note) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅医生可调整后续护理材料");
        }
        MaterialSubstitution s = get(id);
        if (note == null || note.isBlank()) throw new BusinessException("请填写后续护理材料调整意见");

        MedicalOrder order = new MedicalOrder();
        order.setOrderNo(ApplicationService.generateNo("YZ"));
        order.setPatient(s.getPatient());
        order.setPlan(s.getPlan());
        order.setVisit(s.getVisit());
        order.setDoctor(userRepository.findById(me.id()).orElse(null));
        order.setOrderType(OrderType.CARE_PLAN_CHANGE);
        order.setContent("替代耗材效果异常，调整后续换药材料：" + note
                + "。（原替代单 " + s.getSubstitutionNo() + "：因[" + s.getOriginalMaterial().getName()
                + "]缺货改用[" + s.getSubstituteMaterial().getName() + "]，家属已签字知情）");
        order.setDiagnosisBasis("替代材料伤口随访/风险事件提示，回看替代原因与知情签字后调整");
        order.setStatus(OrderStatus.PROPOSED);
        order = orderRepository.save(order);

        s.setMaterialAdjustNote(note);
        s.setAdjustOrder(order);
        s.setMaterialAdjustedAt(LocalDateTime.now());
        s = substitutionRepository.save(s);
        auditService.log(me, "耗材替代", "医生调整后续护理材料", s.getId(), s.getSubstitutionNo(),
                s.getPatient().getId(), note + "；调整医嘱 " + order.getOrderNo());
        return s;
    }

    // ===== 医保报销差异测算 =====

    private Map<String, Object> diff(Material original, Material substitute, int qty, BedApplication app) {
        PriceInfo po = priceOf(original);
        PriceInfo ps = priceOf(substitute);
        BigDecimal oAmount = money(po.price.multiply(BigDecimal.valueOf(qty)));
        BigDecimal sAmount = money(ps.price.multiply(BigDecimal.valueOf(qty)));
        boolean eligible = app != null && Boolean.TRUE.equals(app.getInsuranceEligible())
                && app.getPatient().getInsuranceType() != InsuranceType.NONE;
        BigDecimal oIns = eligible && po.reimbursable && po.rate != null
                ? money(oAmount.multiply(po.rate)) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sIns = eligible && ps.reimbursable && ps.rate != null
                ? money(sAmount.multiply(ps.rate)) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("originalUnitPrice", po.price);
        d.put("substituteUnitPrice", ps.price);
        d.put("originalInsuranceCategory", po.insuranceCategory);
        d.put("substituteInsuranceCategory", ps.insuranceCategory);
        d.put("originalReimbRate", po.rate);
        d.put("substituteReimbRate", ps.rate);
        d.put("originalReimbursable", po.reimbursable);
        d.put("substituteReimbursable", ps.reimbursable);
        d.put("originalInsuranceAmount", oIns);
        d.put("originalSelfAmount", money(oAmount.subtract(oIns)));
        d.put("substituteInsuranceAmount", sIns);
        d.put("substituteSelfAmount", money(sAmount.subtract(sIns)));
        d.put("insuranceDiff", money(sIns.subtract(oIns)));
        d.put("selfPayDiff", money(sAmount.subtract(sIns).subtract(oAmount.subtract(oIns))));
        return d;
    }

    private void applyDiff(MaterialSubstitution s, Map<String, Object> d) {
        s.setOriginalUnitPrice((BigDecimal) d.get("originalUnitPrice"));
        s.setSubstituteUnitPrice((BigDecimal) d.get("substituteUnitPrice"));
        s.setOriginalInsuranceCategory((String) d.get("originalInsuranceCategory"));
        s.setSubstituteInsuranceCategory((String) d.get("substituteInsuranceCategory"));
        s.setOriginalReimbRate((BigDecimal) d.get("originalReimbRate"));
        s.setSubstituteReimbRate((BigDecimal) d.get("substituteReimbRate"));
        s.setOriginalInsuranceAmount((BigDecimal) d.get("originalInsuranceAmount"));
        s.setOriginalSelfAmount((BigDecimal) d.get("originalSelfAmount"));
        s.setSubstituteInsuranceAmount((BigDecimal) d.get("substituteInsuranceAmount"));
        s.setSubstituteSelfAmount((BigDecimal) d.get("substituteSelfAmount"));
        s.setInsuranceDiff((BigDecimal) d.get("insuranceDiff"));
        s.setSelfPayDiff((BigDecimal) d.get("selfPayDiff"));
    }

    private record PriceInfo(BigDecimal price, boolean reimbursable, String insuranceCategory,
                             BigDecimal rate) {}

    private PriceInfo priceOf(Material m) {
        ChargeCatalog c = m.getCatalog() != null
                ? catalogRepository.findById(m.getCatalog().getId()).orElse(null) : null;
        if (c != null) {
            return new PriceInfo(c.getUnitPrice(), Boolean.TRUE.equals(c.getReimbursable()),
                    c.getInsuranceCategory() == null ? "丙类" : c.getInsuranceCategory(),
                    c.getReimbursementRate());
        }
        return new PriceInfo(m.getUnitCost() != null ? m.getUnitCost() : BigDecimal.ZERO,
                false, "丙类", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    /** 可能影响说明（模板，护士可修改） */
    private String buildImpact(Material original, Material sub) {
        StringBuilder sb = new StringBuilder();
        sb.append("替代品[").append(sub.getName())
                .append(sub.getSpec() == null ? "" : " " + sub.getSpec()).append("]与医嘱指定[")
                .append(original.getName()).append("]在吸收量、黏贴牢固度、更换频次上可能存在差异；");
        String n = sub.getName() == null ? "" : sub.getName();
        if (n.contains("水胶体") || n.contains("泡沫") || n.contains("透明")) {
            sb.append("该类敷料密闭性较强，若伤口已感染或渗液过多不宜使用，需加强观察；");
        }
        sb.append("个别患者可能出现敷料接触部位红斑、瘙痒等过敏反应，出现后应立即停用；");
        BigDecimal selfDiff = (BigDecimal) diff(original, sub, 1, null).get("selfPayDiff");
        if (selfDiff.signum() > 0) sb.append("替代品医保报销比例/类别不同，本次家属自费可能增加；");
        else sb.append("替代品报销后家属负担不高于原品；");
        sb.append("换药护理服务项目本身不变。");
        return sb.toString();
    }

    private BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String nz(Object o) { return o == null ? "" : o.toString(); }
}
