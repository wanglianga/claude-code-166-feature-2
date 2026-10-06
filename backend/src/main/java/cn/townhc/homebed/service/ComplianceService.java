package cn.townhc.homebed.service;

import cn.townhc.homebed.common.BusinessException;
import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.domain.enums.DomainEnums.*;
import cn.townhc.homebed.repository.*;
import cn.townhc.homebed.security.LoginUser;
import cn.townhc.homebed.security.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 医保合规自动初审 + 经办复核。
 * 规则（可解释）：
 *  R1 重复收费：同患者同项目同一天出现多次，且目录规定每日限 1 次
 *  R2 超频次：超过目录 dailyLimit
 *  R3 无家属签字：服务对应巡诊未取得家属签字
 *  R4 医保资格存疑：申请评估医保资格不符合，却包含报销项目
 *  R5 冷链断链风险：冷链药品未登记交接温度
 *  R6 医保目录变化：出账后目录版本（比例/价格/可报状态）调整，按出账快照还原
 *  R7 缺少现场照片证据：收费护理项目对应巡诊无任何照片
 *  R8 缺货替代材料：须有医生确认与家属知情签字，医保报销差异已告知；自费增加行重点复核
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceService {

    private final ComplianceRecordRepository complianceRepository;
    private final BillItemRepository billItemRepository;
    private final BillRepository billRepository;
    private final ChargeCatalogRepository catalogRepository;
    private final VisitRepository visitRepository;
    private final VisitPhotoRepository photoRepository;
    private final MaterialSubstitutionRepository substitutionRepository;
    private final SysUserRepository userRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Issue(String ruleCode, String severity, String message, List<String> relatedItemNos) {}

    @Transactional
    public ComplianceRecord autoReview(Bill bill) {
        List<BillItem> items = billItemRepository.findByBillId(bill.getId());
        List<Issue> issues = new ArrayList<>();

        // R1/R2 同项目同天次数
        Map<String, List<BillItem>> byCodeDate = new LinkedHashMap<>();
        for (BillItem it : items) {
            byCodeDate.computeIfAbsent(it.getItemCode() + "@" + it.getServiceDate(), k -> new ArrayList<>()).add(it);
        }
        Map<Long, ChargeCatalog> catCache = new HashMap<>();
        for (var entry : byCodeDate.entrySet()) {
            List<BillItem> group = entry.getValue();
            if (group.size() <= 1) continue;
            BillItem first = group.get(0);
            ChargeCatalog cat = first.getItemCode() == null ? null : loadCatalogByCode(first.getItemCode(), catCache);
            Integer limit = cat != null && cat.getDailyLimit() != null ? cat.getDailyLimit() : 1;
            if (group.size() > limit) {
                boolean dupRule = limit == 1;
                issues.add(new Issue(
                        dupRule ? "R1" : "R2",
                        "VIOLATION",
                        (dupRule ? "疑似重复收费：" : "超过每日频次限制：")
                                + first.getItemName() + " 在 " + first.getServiceDate()
                                + " 出现 " + group.size() + " 次，目录限 " + limit + " 次/日",
                        group.stream().map(BillItem::getSourceNo).filter(Objects::nonNull).distinct().toList()));
            }
        }

        // R3/R5/R7 逐条核查
        Map<String, Visit> visitByNo = new HashMap<>();
        for (Visit v : visitRepository.findAll()) {
            if (v.getVisitNo() != null) visitByNo.put(v.getVisitNo(), v);
        }
        Set<Long> photoVisitIds = new HashSet<>();
        for (BillItem it : items) {
            Visit visit = null;
            if (it.getSourceNo() != null
                    && (it.getSourceType() == BillSourceType.VISIT_CARE
                    || it.getSourceType() == BillSourceType.MATERIAL)) {
                visit = visitByNo.get(it.getSourceNo());
            }
            if (visit != null) {
                boolean hasPhoto = photoVisitIds.contains(visit.getId());
                if (!hasPhoto) {
                    hasPhoto = !photoRepository.findByVisitId(visit.getId()).isEmpty();
                    if (hasPhoto) photoVisitIds.add(visit.getId());
                }
                if (it.getSourceType() == BillSourceType.VISIT_CARE
                        && (visit.getFamilySignPath() == null || visit.getFamilySignPath().isBlank())) {
                    issues.add(new Issue("R3", "VIOLATION",
                            "项目[" + it.getItemName() + "]对应巡诊 " + visit.getVisitNo()
                                    + " 缺少家属签字，不符合收费确认要求",
                            List.of(visit.getVisitNo())));
                }
                if (it.getCategory() == CatalogCategory.COLDCHAIN_DRUG
                        && it.getNote() != null && it.getNote().contains("未登记冷链温度")) {
                    issues.add(new Issue("R5", "WARNING",
                            "冷链药品[" + it.getItemName() + "]未登记交接温度，存在断链争议风险",
                            List.of(it.getSourceNo() == null ? "-" : it.getSourceNo())));
                }
                if (it.getSourceType() == BillSourceType.VISIT_CARE && !hasPhoto
                        && it.getCategory() != CatalogCategory.VISIT_FEE) {
                    issues.add(new Issue("R7", "WARNING",
                            "护理项目[" + it.getItemName() + "]对应巡诊 " + visit.getVisitNo()
                                    + " 未上传现场照片，证据链不完整",
                            List.of(visit.getVisitNo())));
                }
            }
        }

        // R4 医保资格
        if (!Boolean.TRUE.equals(bill.getApplication().getInsuranceEligible())) {
            boolean hasReimburse = items.stream().anyMatch(i -> Boolean.TRUE.equals(i.getReimbursable())
                    && i.getInsuranceAmount() != null && i.getInsuranceAmount().signum() > 0);
            if (hasReimburse) {
                issues.add(new Issue("R4", "VIOLATION",
                        "建床评估中医保资格为“不符合”，但账单含医保报销金额，需经办核实参保与待遇资格",
                        List.of(bill.getBillNo())));
            }
        }

        // R6 目录版本变化（与当前有效目录比对快照）
        for (BillItem it : items) {
            ChargeCatalog current = loadCatalogByCode(it.getItemCode(), catCache);
            if (current != null && current.getActive() != null && current.getActive()) {
                boolean changed = false;
                String what = "";
                if (!current.getUnitPrice().equals(it.getUnitPrice())) { changed = true; what += "价格 "; }
                if (!Objects.equals(current.getReimbursementRate(), it.getReimbursementRate())) { changed = true; what += "报销比例 "; }
                if (!Objects.equals(current.getReimbursable(), it.getReimbursable())) { changed = true; what += "可报状态 "; }
                if (changed) {
                    issues.add(new Issue("R6", "WARNING",
                            "项目[" + it.getItemName() + "]医保目录已调整（" + what + "变化），本行按出账时版本快照结算："
                                    + it.getServiceDate() + " 单价 " + it.getUnitPrice()
                                    + "、比例 " + it.getReimbursementRate(),
                            List.of(it.getSourceNo() == null ? bill.getBillNo() : it.getSourceNo())));
                }
            }
        }

        // R8 缺货替代材料：核查家属知情签字与医保报销差异告知
        for (BillItem it : items) {
            if (it.getSourceType() != BillSourceType.MATERIAL || it.getNote() == null
                    || !it.getNote().contains("缺货替代单")) continue;
            Visit visit = it.getSourceNo() == null ? null : visitByNo.get(it.getSourceNo());
            if (visit == null) continue;
            List<MaterialSubstitution> subs = substitutionRepository.findByVisitId(visit.getId()).stream()
                    .filter(s -> s.getStatus() == SubstituteStatus.EXECUTED)
                    .filter(s -> s.getOutTransaction() != null
                            && it.getSourceId() != null
                            && it.getSourceId().equals(s.getOutTransaction().getId()))
                    .toList();
            for (MaterialSubstitution s : subs) {
                if (s.getFamilySignPath() == null || s.getFamilySignPath().isBlank()) {
                    issues.add(new Issue("R8", "VIOLATION",
                            "替代材料[" + it.getItemName() + "]对应替代单 " + s.getSubstitutionNo()
                                    + " 缺少家属知情签字，医保报销差异未经家属确认，不予报销",
                            List.of(visit.getVisitNo())));
                } else if (s.getSelfPayDiff() != null && s.getSelfPayDiff().signum() > 0) {
                    issues.add(new Issue("R8", "WARNING",
                            "替代材料[" + it.getItemName() + "]与医嘱原品[" + s.getOriginalMaterial().getName()
                                    + "]医保报销存在差异（报销额变化 " + s.getInsuranceDiff()
                                    + "、家属自费增加 " + s.getSelfPayDiff() + "），替代单 "
                                    + s.getSubstitutionNo() + " 已有医生确认与家属签字("
                                    + s.getFamilySignName() + ")，准予按替代材料目录报销",
                            List.of(visit.getVisitNo())));
                }
            }
        }

        int violation = (int) issues.stream().filter(i -> i.severity().equals("VIOLATION")).count();
        int warning = issues.size() - violation;
        int score = Math.max(0, 100 - violation * 18 - warning * 7);

        ComplianceRecord record = complianceRepository.findByBillId(bill.getId()).orElseGet(() -> {
            ComplianceRecord r = new ComplianceRecord();
            r.setBill(bill);
            return r;
        });
        record.setIssuesJson(toJson(issues));
        record.setAutoScore(score);
        record.setStatus(violation > 0 ? ComplianceStatus.VIOLATION : ComplianceStatus.COMPLIANT);
        record.setAgencyName(record.getAgencyName() == null ? "镇医保服务站" : record.getAgencyName());
        return complianceRepository.save(record);
    }

    private ChargeCatalog loadCatalogByCode(String code, Map<Long, ChargeCatalog> cache) {
        return catalogRepository.findAllByOrderByCategoryAscCodeAsc().stream()
                .filter(c -> c.getCode().equals(code))
                .findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Issue> issues(ComplianceRecord r) {
        if (r.getIssuesJson() == null || r.getIssuesJson().isBlank()) return List.of();
        try {
            return objectMapper.readValue(r.getIssuesJson(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析合规问题失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(List<Issue> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<ComplianceRecord> records;
        if (status != null && !status.isBlank()) {
            records = complianceRepository.findByStatusOrderByCreatedAtDesc(ComplianceStatus.valueOf(status));
        } else {
            records = complianceRepository.findAll();
            records.sort(Comparator.comparing(ComplianceRecord::getCreatedAt).reversed());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ComplianceRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("record", r);
            m.put("billNo", r.getBill().getBillNo());
            m.put("patientName", r.getBill().getPatient().getName());
            m.put("totalAmount", r.getBill().getTotalAmount());
            m.put("issues", issues(r));
            result.add(m);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ComplianceRecord byBill(Long billId) {
        return complianceRepository.findByBillId(billId).orElse(null);
    }

    @Transactional
    public ComplianceRecord rerunByBill(Long billId) {
        Bill bill = billRepository.findById(billId)
                .orElseThrow(() -> new BusinessException(404, "账单不存在"));
        return autoReview(bill);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        ComplianceRecord r = complianceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "合规记录不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("record", r);
        m.put("issues", issues(r));
        return m;
    }

    /** 医保经办复核结论 */
    @Transactional
    public ComplianceRecord audit(Long id, ComplianceStatus result, String comment,
                                  String conclusion, String agencyName) {
        LoginUser me = SecurityUtils.current();
        ComplianceRecord r = complianceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "合规记录不存在"));
        r.setStatus(result);
        r.setAuditor(userRepository.findById(me.id()).orElse(null));
        r.setAuditorComment(comment);
        r.setAuditConclusion(conclusion);
        r.setAgencyName(agencyName);
        r.setAuditedAt(LocalDateTime.now());
        r = complianceRepository.save(r);

        Bill bill = r.getBill();
        if (result == ComplianceStatus.COMPLIANT && bill.getStatus() == BillStatus.CONFIRMED) {
            bill.setStatus(BillStatus.INSURANCE_REVIEWED);
            billRepository.save(bill);
        }
        auditService.log(me, "医保合规", "经办复核", r.getId(), bill.getBillNo(),
                bill.getPatient().getId(), "结论=" + result.label + "；" + comment);
        return r;
    }
}
