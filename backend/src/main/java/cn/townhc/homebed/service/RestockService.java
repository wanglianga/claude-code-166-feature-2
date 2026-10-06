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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 耗材补货任务闭环。
 * 家属拒绝替代时自动生成加急补货任务；管理者/财务报采购、登记到货入库，
 * 系统自动为等待原品的患者安排一次“原品补换药”上门并恢复护理计划。
 */
@Service
@RequiredArgsConstructor
public class RestockService {

    private final RestockTaskRepository restockRepository;
    private final MaterialRepository materialRepository;
    private final MaterialTransactionRepository txRepository;
    private final MaterialSubstitutionRepository substitutionRepository;
    private final VisitRepository visitRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final SysUserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<RestockTask> list(String status) {
        List<RestockTask> result;
        if (status != null && !status.isBlank()) {
            result = restockRepository.findByStatusOrderByUrgentDescCreatedAtDesc(
                    RestockStatus.valueOf(status));
        } else {
            result = restockRepository.findAllByOrderByCreatedAtDesc();
        }
        result.forEach(t -> {
            t.getMaterial().getName();
            t.getMaterial().getSpec();
            t.getMaterial().getUnit();
            t.getMaterial().getColdChain();
            if (t.getReporter() != null) t.getReporter().getName();
            if (t.getHandler() != null) t.getHandler().getName();
        });
        return result;
    }

    /** 家属拒绝替代 → 自动加急补货任务（需求量至少满足本次用量与安全库存） */
    @Transactional
    public RestockTask createForSubstitution(MaterialSubstitution s) {
        LoginUser me = SecurityUtils.currentOrNull();
        Material m = s.getOriginalMaterial();
        int stock = m.getStockQty() == null ? 0 : m.getStockQty();
        int safety = m.getSafetyStock() == null ? 0 : m.getSafetyStock();
        int qty = s.getQuantity() == null ? 1 : s.getQuantity();
        int required = Math.max(qty, safety - stock + qty);

        // 同一材料已有未完成任务时，合并需求量，不重复建单
        List<RestockTask> open = restockRepository
                .findByMaterialIdAndStatusOrderByCreatedAtDesc(m.getId(), RestockStatus.PENDING);
        open.addAll(restockRepository.findByMaterialIdAndStatusOrderByCreatedAtDesc(
                m.getId(), RestockStatus.ORDERED));
        if (!open.isEmpty()) {
            RestockTask first = open.get(0);
            first.setRequiredQty(Math.max(first.getRequiredQty() == null ? 0 : first.getRequiredQty(), required));
            first.setUrgent(true);
            first.setSourceNote((first.getSourceNote() == null ? "" : first.getSourceNote() + "；")
                    + "家属拒绝替代单 " + s.getSubstitutionNo() + "，等待原品补换药");
            return restockRepository.save(first);
        }

        RestockTask t = new RestockTask();
        t.setTaskNo(ApplicationService.generateNo("BH"));
        t.setMaterial(m);
        t.setRequiredQty(required);
        t.setCurrentStockSnapshot(stock);
        t.setUrgent(true);
        t.setStatus(RestockStatus.PENDING);
        t.setSourceNote("家属拒绝替代单 " + s.getSubstitutionNo()
                + "（患者 " + s.getPatient().getName() + "），需原品到货后补换药");
        if (me != null) t.setReporter(userRepository.findById(me.id()).orElse(null));
        t = restockRepository.save(t);
        if (me != null) {
            auditService.log(me, "耗材补货", "自动生成加急补货任务", t.getId(), t.getTaskNo(),
                    s.getPatient().getId(), m.getName() + " 需求 " + required + m.getUnit());
        }
        return t;
    }

    /** 手动创建补货任务（缺货预警/缺货异常） */
    @Transactional
    public RestockTask create(Long materialId, Integer requiredQty, Boolean urgent, String sourceNote) {
        LoginUser me = SecurityUtils.current();
        Material m = materialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(404, "耗材不存在"));
        RestockTask t = new RestockTask();
        t.setTaskNo(ApplicationService.generateNo("BH"));
        t.setMaterial(m);
        t.setRequiredQty(requiredQty == null || requiredQty <= 0
                ? Math.max(1, (m.getSafetyStock() == null ? 0 : m.getSafetyStock())
                        - (m.getStockQty() == null ? 0 : m.getStockQty())) : requiredQty);
        t.setCurrentStockSnapshot(m.getStockQty());
        t.setUrgent(Boolean.TRUE.equals(urgent));
        t.setStatus(RestockStatus.PENDING);
        t.setSourceNote(sourceNote == null ? "手动补货" : sourceNote);
        t.setReporter(userRepository.findById(me.id()).orElse(null));
        t = restockRepository.save(t);
        auditService.log(me, "耗材补货", "创建补货任务", t.getId(), t.getTaskNo(), null,
                m.getName() + " 需求 " + t.getRequiredQty() + m.getUnit());
        return t;
    }

    /** 报采购 */
    @Transactional
    public RestockTask order(Long id, String note) {
        LoginUser me = SecurityUtils.current();
        RestockTask t = must(id);
        if (t.getStatus() != RestockStatus.PENDING) {
            throw new BusinessException("仅待补货任务可报采购，当前：" + t.getStatus().label);
        }
        t.setStatus(RestockStatus.ORDERED);
        t.setOrderedAt(LocalDateTime.now());
        t.setHandler(userRepository.findById(me.id()).orElse(null));
        t.setHandleNote(note);
        t = restockRepository.save(t);
        auditService.log(me, "耗材补货", "报采购", t.getId(), t.getTaskNo(), null, note);
        return t;
    }

    public record ArriveCommand(Integer quantity, String batchNo, LocalDate expiryDate,
                                Double coldChainTemp, String note, LocalDateTime makeupTime) {}

    /**
     * 到货：登记入库；若该补货任务源于家属拒绝替代，自动安排一次原品补换药上门，
     * 并把对应缺货异常闭环。
     */
    @Transactional
    public RestockTask arrive(Long id, ArriveCommand cmd) {
        LoginUser me = SecurityUtils.current();
        RestockTask t = must(id);
        if (t.getStatus() != RestockStatus.PENDING && t.getStatus() != RestockStatus.ORDERED) {
            throw new BusinessException("仅待补货/已报采购任务可登记到货，当前：" + t.getStatus().label);
        }
        Material m = t.getMaterial();
        int qty = cmd.quantity() == null || cmd.quantity() <= 0
                ? (t.getRequiredQty() == null ? 1 : t.getRequiredQty()) : cmd.quantity();
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
                + (cmd.note() == null ? "" : "：" + cmd.note()));
        tx.setOccurredAt(LocalDateTime.now());
        txRepository.save(tx);

        t.setStatus(RestockStatus.ARRIVED);
        t.setArrivedAt(LocalDateTime.now());
        t.setHandler(userRepository.findById(me.id()).orElse(null));
        t.setStockInTxnNo(String.valueOf(tx.getId()));
        t.setHandleNote(cmd.note());
        final LocalDateTime arrivedAt = t.getArrivedAt();
        final String taskNo = t.getTaskNo();

        // 家属拒绝替代等待原品的患者：自动安排补换药上门
        List<MaterialSubstitution> waiters = substitutionRepository.findByRestockTaskId(id).stream()
                .filter(s -> s.getStatus() == SubstituteStatus.REJECTED_BY_FAMILY)
                .toList();
        StringBuilder makeup = new StringBuilder();
        for (MaterialSubstitution s : waiters) {
            Visit visit = s.getVisit();
            LocalDateTime at = cmd.makeupTime() != null ? cmd.makeupTime()
                    : LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
            Visit makeupVisit = new Visit();
            makeupVisit.setVisitNo(ApplicationService.generateNo("XZ"));
            makeupVisit.setPlan(s.getPlan() != null ? s.getPlan() : visit.getPlan());
            makeupVisit.setPatient(s.getPatient());
            makeupVisit.setNurse(visit.getNurse());
            makeupVisit.setScheduledAt(at);
            makeupVisit.setStatus(VisitStatus.SCHEDULED);
            makeupVisit.setEstimatedTravelMinutes(visit.getEstimatedTravelMinutes());
            makeupVisit.setArrangementNote("补货任务 " + t.getTaskNo() + " 到货（" + m.getName()
                    + " x" + qty + "），家属此前不同意替代，本次安排原品补换药；原替代单 "
                    + s.getSubstitutionNo() + "；此前护理调整：" + s.getPlanAdjustment());
            Visit savedMakeup = visitRepository.save(makeupVisit);
            final Visit makeupRef = savedMakeup;
            makeup.append(savedMakeup.getVisitNo()).append(" ");

            // 闭环该替代单对应的缺货异常
            exceptionRepository.findByVisitId(visit.getId()).stream()
                    .filter(e -> e.getType() == ExceptionType.SUPPLY_SHORTAGE
                            && e.getStatus() != ExceptionStatus.RESOLVED)
                    .findFirst()
                    .ifPresent(e -> {
                        e.setStatus(ExceptionStatus.RESOLVED);
                        e.setHandler(userRepository.findById(me.id()).orElse(null));
                        e.setHandledAt(LocalDateTime.now());
                        e.setHandleConclusion("原品已于 " + arrivedAt + " 到货入库，补换药任务 "
                                + makeupRef.getVisitNo() + " 已自动安排（补货任务 " + taskNo + "）");
                        exceptionRepository.save(e);
                    });
            auditService.log(me, "耗材补货", "到货并安排补换药", t.getId(), t.getTaskNo(),
                    s.getPatient().getId(),
                    "补换药巡诊 " + savedMakeup.getVisitNo() + " 时间 " + at);
        }
        t.setMakeupVisitNo(makeup.length() == 0 ? null : makeup.toString().trim());
        t = restockRepository.save(t);
        auditService.log(me, "耗材补货", "到货入库", t.getId(), t.getTaskNo(), null,
                m.getName() + " x" + qty + (t.getMakeupVisitNo() != null
                        ? "；补换药任务 " + t.getMakeupVisitNo() : ""));
        return t;
    }

    @Transactional
    public RestockTask cancel(Long id, String note) {
        LoginUser me = SecurityUtils.current();
        RestockTask t = must(id);
        t.setStatus(RestockStatus.CANCELLED);
        t.setHandleNote(note);
        t = restockRepository.save(t);
        auditService.log(me, "耗材补货", "取消补货任务", t.getId(), t.getTaskNo(), null, note);
        return t;
    }

    private RestockTask must(Long id) {
        return restockRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "补货任务不存在"));
    }
}
